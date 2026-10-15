package com.yingzao.core.plan;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.web.BizException;

/**
 * 异步任务计划内核(information.md「Plan 必迁」裁决兑现):
 * 老 plan(executeTime 排队+外部 Python 执行器轮询回调)内核化——任务落库同事务、
 * 到期调度执行(处理器=YzPlanHandler SPI bean)、失败指数退避重试、超限死信可见可重试。
 *
 * 与事件总线的分工:总线管「广播与回程」(领域事件,一事件多消费者),本设施管「待办」
 * (定点任务,一任务一处理器:导出生成/批量重算/对账跑批等);老 plan 的外部执行器
 * (HTTP 回调型任务)为 V2 对接口,当前处理器均为进程内 Spring bean。
 * 常量与语义对齐事件总线(status 0/1/2/3 + 死信 requeue),另加 4=已取消(计划可撤)。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_RETRYING = 2;
    public static final int STATUS_DEAD = 3;
    public static final int STATUS_CANCELLED = 4;

    private static final int CLAIM_TIMEOUT_MINUTES = 30;

    public static final int CODE_PLAN_NOT_FOUND = 123010;
    public static final int CODE_PLAN_STATE = 123011;
    public static final int CODE_NO_HANDLER = 123012;
    public static final int CODE_FORBIDDEN = 123013;
    public static final int CODE_PARAM = 123014;

    private final JdbcTemplate jdbc;
    private final MessageSourceWrapper messages;
    private final Map<String, YzPlanHandler> handlers = new HashMap<>();
    private final long backoffSeconds;
    private final long backoffCapSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanService(JdbcTemplate jdbc, org.springframework.context.MessageSource messages,
            List<YzPlanHandler> planHandlers,
            @Value("${yingzao.plan.backoff-seconds:60}") long backoffSeconds,
            @Value("${yingzao.plan.backoff-cap-seconds:3600}") long backoffCapSeconds) {
        this.jdbc = jdbc;
        this.messages = new MessageSourceWrapper(messages);
        this.backoffSeconds = Math.max(1, backoffSeconds);
        this.backoffCapSeconds = Math.max(this.backoffSeconds, backoffCapSeconds);
        for (YzPlanHandler h : planHandlers) {
            handlers.put(h.handler(), h);
        }
        log.info("yz plan facility started, handlers: {}", handlers.keySet());
    }

    public static class MessageSourceWrapper {
        private final org.springframework.context.MessageSource messages;

        public MessageSourceWrapper(org.springframework.context.MessageSource messages) {
            this.messages = messages;
        }

        public String get(String key) {
            return messages.getMessage(key, null, LocaleContextHolder.getLocale());
        }
    }

    // ---- 提交 ----

    /**
     * 提交任务(业务事务内调用=落库同事务,提交后立即尝试执行一次;无事务立即执行)。
     *
     * @param handler    处理器注册名(必须已注册,否则 123012)
     * @param bizKey     业务键(幂等/对账,可空)
     * @param params     参数对象(序列化为 JSON 透传;可空)
     * @param maxRetries 失败重试上限(>=1;超限转死信)
     * @param runAt      到期时刻(null=立即可执行;早于现在同样立即)
     */
    public long submit(long orgId, String handler, String bizKey, Object params,
            Integer maxRetries, java.time.LocalDateTime runAt, Long createdBy) {
        require(handler != null && !handler.isBlank(), CODE_PARAM, "plan.param");
        require(maxRetries == null || maxRetries >= 1, CODE_PARAM, "plan.param");
        if (!handlers.containsKey(handler)) {
            throw new BizException(CODE_NO_HANDLER, messages.get("plan.nohandler"));
        }
        String paramsJson = null;
        if (params != null) {
            try {
                paramsJson = params instanceof String p ? p
                        : objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                throw new BizException(CODE_PARAM, messages.get("plan.param"));
            }
        }
        jdbc.update("""
                insert into yz_plan(org_id, handler, biz_key, params, max_retries, next_run_time, created_by)
                values (?,?,?,?,?,?,?)
                """, orgId, handler.trim(), blankToNull(bizKey), paramsJson,
                maxRetries == null ? 3 : maxRetries, runAt, createdBy);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        final long pid = id;
        afterCommit(() -> dispatchPlan(pid));
        return id;
    }

    // ---- 执行 ----

    /** 执行单条:条件认领 → 处理器执行 → 成功落结果 / 失败退避重试或死信。 */
    public void dispatchPlan(long planId) {
        if (!claim(planId)) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, handler, biz_key, params, status, retry_count, max_retries
                from yz_plan where id = ? and claimed_at >= date_sub(now(), interval 1 minute)
                """, planId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> plan = rows.get(0);
        String handlerName = (String) plan.get("handler");
        YzPlanHandler handler = handlers.get(handlerName);
        if (handler == null) {
            fail(planId, ((Number) plan.get("retry_count")).intValue(),
                    ((Number) plan.get("max_retries")).intValue(),
                    "handler not registered: " + handlerName);
            return;
        }
        long orgId = ((Number) plan.get("org_id")).longValue();
        JsonNode params = null;
        String raw = (String) plan.get("params");
        if (raw != null && !raw.isBlank()) {
            try {
                params = objectMapper.readTree(raw);
            } catch (Exception e) {
                fail(planId, ((Number) plan.get("retry_count")).intValue(),
                        ((Number) plan.get("max_retries")).intValue(),
                        "params parse: " + e.getMessage());
                return;
            }
        }
        try {
            YzPlanHandler.PlanResult result = handler.execute(orgId, planId, params);
            jdbc.update("update yz_plan set status = ?, result = ? where id = ?",
                    STATUS_DONE, result == null ? null : result.result(), planId);
        } catch (Throwable t) {
            log.warn("plan handler {} failed, planId={}", handlerName, planId, t);
            fail(planId, ((Number) plan.get("retry_count")).intValue(),
                    ((Number) plan.get("max_retries")).intValue(),
                    handlerName + ": " + t.getMessage());
        }
    }

    /** 调度器入口:捡起到期任务(0 待执行 / 2 重试中,含崩溃超时回收),逐条执行。 */
    public void dispatchDue(int limit) {
        List<Long> dueIds = jdbc.queryForList("""
                select id from yz_plan
                where status in (0, 2) and (next_run_time is null or next_run_time <= now())
                  and (claimed_at is null or claimed_at < date_sub(now(), interval %d minute))
                order by id limit ?
                """.formatted(CLAIM_TIMEOUT_MINUTES), Long.class, limit);
        for (Long id : dueIds) {
            dispatchPlan(id);
        }
    }

    // ---- 管理动作 ----

    /** 取消(仅 0 待执行/2 重试中;死信走 retry 重跑,成功不可撤)。 */
    public void cancel(long planId) {
        int updated = jdbc.update(
                "update yz_plan set status = ? where id = ? and status in (0, 2)",
                STATUS_CANCELLED, planId);
        if (updated == 0) {
            Map<String, Object> plan = find(planId);
            if (plan == null) {
                throw new BizException(CODE_PLAN_NOT_FOUND, messages.get("plan.notfound"));
            }
            throw new BizException(CODE_PLAN_STATE, messages.get("plan.state"));
        }
    }

    /** 死信/重试中任务重跑:重置为待执行并立即执行(重试计数清零)。 */
    public void retry(long planId) {
        int updated = jdbc.update("""
                update yz_plan set status = 0, retry_count = 0, next_run_time = null,
                    claimed_at = null, last_error = null
                where id = ? and status in (2, 3)
                """, planId);
        if (updated == 0) {
            Map<String, Object> plan = find(planId);
            if (plan == null) {
                throw new BizException(CODE_PLAN_NOT_FOUND, messages.get("plan.notfound"));
            }
            throw new BizException(CODE_PLAN_STATE, messages.get("plan.state"));
        }
        afterCommit(() -> dispatchPlan(planId));
    }

    public Map<String, Object> get(long planId) {
        Map<String, Object> plan = find(planId);
        if (plan == null) {
            throw new BizException(CODE_PLAN_NOT_FOUND, messages.get("plan.notfound"));
        }
        return plan;
    }

    public Map<String, Object> page(Integer status, String handler, Long orgId,
            int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (handler != null && !handler.isBlank()) {
            where.append(" and handler = ?");
            args.add(handler.trim());
        }
        if (orgId != null) {
            where.append(" and org_id = ?");
            args.add(orgId);
        }
        Long total = jdbc.queryForObject("select count(*) from yz_plan" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, handler, biz_key, params, status, max_retries, retry_count,
                       next_run_time, result, last_error, created_by, created_at, updated_at
                from yz_plan""" + where + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> stats() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, count(*) as cnt from yz_plan group by status");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int st = STATUS_PENDING; st <= STATUS_CANCELLED; st++) {
            result.put("status" + st, 0);
        }
        for (Map<String, Object> row : rows) {
            result.put("status" + ((Number) row.get("status")).intValue(),
                    ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    // ---- 内部 ----

    private boolean claim(long planId) {
        jdbc.update("""
                update yz_plan set claimed_at = now()
                where id = ? and status in (0, 2)
                  and (next_run_time is null or next_run_time <= now())
                  and (claimed_at is null or claimed_at < date_sub(now(), interval %d minute))
                """.formatted(CLAIM_TIMEOUT_MINUTES), planId);
        Integer n = jdbc.queryForObject("""
                select count(*) from yz_plan where id = ? and claimed_at >= date_sub(now(), interval 1 minute)
                """, Integer.class, planId);
        return n != null && n > 0;
    }

    private void fail(long planId, int retryCount, int maxRetries, String error) {
        int next = retryCount + 1;
        if (next >= maxRetries) {
            jdbc.update("update yz_plan set status = ?, retry_count = ?, last_error = ?, claimed_at = null where id = ?",
                    STATUS_DEAD, next, error, planId);
        } else {
            long backoff = Math.min(backoffSeconds * (1L << Math.min(retryCount, 16)), backoffCapSeconds);
            jdbc.update("""
                    update yz_plan set status = ?, retry_count = ?, last_error = ?,
                        next_run_time = date_add(now(), interval ? second), claimed_at = null
                    where id = ?
                    """, STATUS_RETRYING, next, error, backoff, planId);
        }
    }

    private Map<String, Object> find(long planId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, handler, biz_key, params, status, max_retries, retry_count,
                       next_run_time, result, last_error, created_by, created_at, updated_at
                from yz_plan where id = ?
                """, planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, messages.get(key));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
