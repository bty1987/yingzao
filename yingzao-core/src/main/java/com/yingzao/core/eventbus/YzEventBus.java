package com.yingzao.core.eventbus;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 领域事件总线(内核平台设施):outbox 落库 + 事务提交后立即投递 + 失败退避重试 + 死信可见。
 * 红线来源 docs/rules/information.md:老 messagequeuing 近半事件失败无重试悄然丢失——
 * 营造三件套:①消费确认(status=1,含「无消费者」归档);②重试(status=2,指数退避,投递器崩溃
 * 5 分钟后可再认领);③死信(status=3,last_error + 管理端点可见可 requeue)。
 * 发布姿势:业务方法内 bus.publish(event)——落库语句加入当前事务,事件与业务变更同生共死;
 * 提交后同步投递一轮,失败交给 YzEventDispatcher 周期调度。插件订阅姿势:实现 {@link YzEventConsumer}。
 * 契约事件类放 addons-api;事件只携带 id 与不可变快照(RFC §3.3)。
 */
@Service
public class YzEventBus {

    private static final Logger log = LoggerFactory.getLogger(YzEventBus.class);

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONSUMED = 1;
    public static final int STATUS_RETRYING = 2;
    public static final int STATUS_DEAD = 3;

    private static final int CLAIM_TIMEOUT_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final Map<String, List<YzEventConsumer>> consumersByType = new HashMap<>();
    private final long backoffSeconds;
    private final long backoffCapSeconds;
    private final int defaultMaxRetries;

    public YzEventBus(JdbcTemplate jdbc, List<YzEventConsumer> consumers,
            @Value("${yingzao.events.backoff-seconds:60}") long backoffSeconds,
            @Value("${yingzao.events.backoff-cap-seconds:3600}") long backoffCapSeconds,
            @Value("${yingzao.events.max-retries:5}") int defaultMaxRetries) {
        this.jdbc = jdbc;
        this.backoffSeconds = Math.max(1, backoffSeconds);
        this.backoffCapSeconds = Math.max(this.backoffSeconds, backoffCapSeconds);
        this.defaultMaxRetries = Math.max(1, defaultMaxRetries);
        for (YzEventConsumer consumer : consumers) {
            for (String type : consumer.eventTypes()) {
                consumersByType.computeIfAbsent(type, k -> new ArrayList<>()).add(consumer);
            }
        }
        log.info("yz event bus started, consumers: {}", consumersByType.keySet());
    }

    /** 业务事务内发布:落 outbox(同事务),提交后立即投递;无事务时落库后直接投递。 */
    public void publish(Object event) {
        publish(event, null);
    }

    public void publish(Object event, String eventKey) {
        String type = event.getClass().getSimpleName();
        String payload;
        try {
            payload = EventBusObjectMapper.get().writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("event serialize failed: " + type, e);
        }
        jdbc.update("insert into yz_event_task(event_type, payload, event_key, max_retries) values (?,?,?,?)",
                type, payload, eventKey, defaultMaxRetries);
        long taskId = jdbc.queryForObject("select last_insert_id()", Long.class);
        afterCommit(() -> dispatchTask(taskId));
    }

    /** 投递单条:认领 → 全部消费者执行 → 全成=消费确认 / 任一败=退避重试或死信。无消费者视为归档。 */
    public void dispatchTask(long taskId) {
        Map<String, Object> task = claim(taskId);
        if (task == null) {
            return;
        }
        String type = (String) task.get("event_type");
        List<YzEventConsumer> consumers = consumersByType.getOrDefault(type, List.of());
        if (consumers.isEmpty()) {
            markConsumed(taskId, null);
            return;
        }
        JsonNode payload;
        try {
            payload = EventBusObjectMapper.get().readTree((String) task.get("payload"));
        } catch (Exception e) {
            fail(taskId, ((Number) task.get("retry_count")).intValue(),
                    ((Number) task.get("max_retries")).intValue(), "payload parse: " + e.getMessage());
            return;
        }
        YzEventRecord record = new YzEventRecord(taskId, type, (String) task.get("event_key"), payload);
        List<String> failures = new ArrayList<>();
        for (YzEventConsumer consumer : consumers) {
            String consumerName = consumer.getClass().getSimpleName();
            // exactly-once 投递语义(V105):重投递/requeue 时跳过已成功消费者,
            // 消除「多消费者部分失败整条重试→已成功者重放」的常态重放;崩溃窗口由业务幂等兜底。
            Integer done = jdbc.queryForObject(
                    "select count(*) from yz_event_consumed where task_id = ? and consumer = ?",
                    Integer.class, taskId, consumerName);
            if (done != null && done > 0) {
                continue;
            }
            try {
                consumer.consume(record);
                jdbc.update("insert ignore into yz_event_consumed(task_id, consumer) values (?,?)",
                        taskId, consumerName);
            } catch (Throwable t) {
                log.warn("event consumer {} failed, taskId={} type={}",
                        consumerName, taskId, type, t);
                failures.add(consumerName + ": " + t.getMessage());
            }
        }
        if (failures.isEmpty()) {
            markConsumed(taskId, null);
        } else {
            fail(taskId, ((Number) task.get("retry_count")).intValue(),
                    ((Number) task.get("max_retries")).intValue(), String.join(" | ", failures));
        }
    }

    /** 调度器入口:捡起到期待投递任务(0 待投递 / 2 重试中,含投递器崩溃超时回收),逐条投递。 */
    public void dispatchDue(int limit) {
        List<Long> dueIds = jdbc.queryForList("""
                select id from yz_event_task
                where status in (0, 2) and (next_retry_time is null or next_retry_time <= now())
                  and (claimed_at is null or claimed_at < date_sub(now(), interval %d minute))
                order by id limit ?
                """.formatted(CLAIM_TIMEOUT_MINUTES), Long.class, limit);
        for (Long id : dueIds) {
            dispatchTask(id);
        }
    }

    /** 管理端 requeue:任何状态重置为待投递并立即触发一轮投递。 */
    public void requeue(long taskId) {
        int updated = jdbc.update("""
                update yz_event_task set status = 0, retry_count = 0, next_retry_time = null,
                    claimed_at = null, last_error = null
                where id = ?
                """, taskId);
        if (updated == 0) {
            throw new BizEventNotFound(taskId);
        }
        afterCommit(() -> dispatchTask(taskId));
    }

    public Map<String, Object> page(Integer status, String eventType, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (eventType != null && !eventType.isBlank()) {
            where.append(" and event_type = ?");
            args.add(eventType.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from yz_event_task" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, event_type, event_key, status, retry_count, max_retries,
                       next_retry_time, claimed_at, last_error, created_at, updated_at
                from yz_event_task""" + where + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> stats() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, count(*) as cnt from yz_event_task group by status
                """);
        Map<String, Object> result = new LinkedHashMap<>();
        for (int s = STATUS_PENDING; s <= STATUS_DEAD; s++) {
            result.put("status" + s, 0);
        }
        for (Map<String, Object> row : rows) {
            result.put("status" + ((Number) row.get("status")).intValue(), ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    // ---- 内部 ----

    /** 条件认领(claimed_at 空或超时)——多实例部署时防双投;单条 SQL,无行锁长持有。 */
    private Map<String, Object> claim(long taskId) {
        jdbc.update("""
                update yz_event_task set claimed_at = now()
                where id = ? and status in (0, 2)
                  and (claimed_at is null or claimed_at < date_sub(now(), interval %d minute))
                """.formatted(CLAIM_TIMEOUT_MINUTES), taskId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, event_type, payload, event_key, status, retry_count, max_retries
                from yz_event_task where id = ? and claimed_at >= date_sub(now(), interval 1 minute)
                """, taskId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> task = rows.get(0);
        int status = ((Number) task.get("status")).intValue();
        return status == STATUS_PENDING || status == STATUS_RETRYING ? task : null;
    }

    private void markConsumed(long taskId, String ignored) {
        jdbc.update("""
                update yz_event_task set status = 1, claimed_at = null, last_error = null,
                    next_retry_time = null where id = ?
                """, taskId);
    }

    private void fail(long taskId, int retryCount, int maxRetries, String error) {
        int next = retryCount + 1;
        if (next >= maxRetries) {
            jdbc.update("""
                    update yz_event_task set status = 3, retry_count = ?, claimed_at = null, last_error = ?
                    where id = ?
                    """, next, trimError(error), taskId);
            log.error("event task {} moved to DEAD after {} attempts", taskId, next);
            return;
        }
        long delay = Math.min(backoffSeconds * (1L << Math.min(next, 20)), backoffCapSeconds);
        jdbc.update("""
                update yz_event_task set status = 2, retry_count = ?, next_retry_time = ?, claimed_at = null,
                    last_error = ? where id = ?
                """, next, new Date(System.currentTimeMillis() + delay * 1000), trimError(error), taskId);
    }

    private String trimError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.warn("post-commit dispatch failed, task left for scheduler: {}", e.getMessage());
                    }
                }
            });
        } else {
            action.run();
        }
    }

    /** requeue 目标不存在(内核事件段 123000)。 */
    public static class BizEventNotFound extends RuntimeException {
        public BizEventNotFound(long taskId) {
            super("event task not found: " + taskId);
        }
    }
}
