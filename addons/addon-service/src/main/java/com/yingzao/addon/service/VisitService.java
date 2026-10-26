package com.yingzao.addon.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 回访任务域(fl-yy 闭环语义):0 待回访→1 已完成(rating 1-5+内容必填)→2 无法完成(原因字典必填);
 * 草稿=可反复保存不改状态(fl-yy 草稿语义,不单设状态列);完成/无法完成后不可再改(append 修正走备注不迁);
 * 三来源:手工(manual)/投诉处理联动(complaint,ComplaintService 调 createFromLink)/
 * 竣工验收联动(acceptance,AcceptanceVisitConsumer 调 createFromLink,exists 查重);
 * terminate(退单)联动归 V2;逾期=plan_time 查询期计算不落列。
 */
@Service
public class VisitService {

    public static final int CODE_PARAM = 380001;
    public static final int CODE_NOT_FOUND = 380002;
    public static final int CODE_BAD_STATE = 380003;
    public static final int CODE_REASON_REQUIRED = 380004;
    public static final int CODE_RATING_INVALID = 380005;
    public static final int CODE_ORDER_NOT_FOUND = 380006;

    public static final int ST_PENDING = 0;
    public static final int ST_FINISHED = 1;
    public static final int ST_FAILED = 2;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;
    private final ServiceSupport support;

    public VisitService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus, ServiceSupport support) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
        this.support = support;
    }

    public record CreateCmd(Long orderId, String planTime) {
    }

    public record FinishCmd(Integer rating, String content) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, long userId) {
        Map<String, Object> order = support.orderSnapshot(orgId, cmd == null ? null : cmd.orderId());
        jdbc.update("""
                insert into service_visit_task(org_id, order_id, order_no, customer_name, biz_type,
                    status, plan_time, created_by)
                values (?,?,?,?, 'manual', 0, ?, ?)
                """, orgId, order.get("orderId"), order.get("orderNo"), order.get("customerName"),
                blankToNull(cmd == null ? null : cmd.planTime()), userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 联动任务生成(投诉/验收消费方调用;exists 查重——同一单同一场景只生成一次)。 */
    @Transactional
    public void createFromLink(long orgId, String bizType, long bizId, long orderId, long userId) {
        Map<String, Object> order = support.orderSnapshot(orgId, orderId);
        Integer exists = jdbc.queryForObject(
                "select count(*) from service_visit_task where org_id = ? and biz_type = ? and order_id = ?",
                Integer.class, orgId, bizType, orderId);
        if (exists != null && exists > 0) {
            return;
        }
        if ("acceptance".equals(bizType)) {
            jdbc.update("""
                    insert into service_visit_task(org_id, order_id, order_no, customer_name, biz_type,
                        biz_id, status, plan_time, created_by)
                    values (?,?,?,?, ?,?, 0, date_add(now(), interval ? day), ?)
                    """, orgId, order.get("orderId"), order.get("orderNo"), order.get("customerName"),
                    bizType, bizId, support.acceptanceVisitDays(orgId), userId);
        } else {
            jdbc.update("""
                    insert into service_visit_task(org_id, order_id, order_no, customer_name, biz_type,
                        biz_id, status, created_by)
                    values (?,?,?,?, ?,?, 0, ?)
                    """, orgId, order.get("orderId"), order.get("orderNo"), order.get("customerName"),
                    bizType, bizId, userId);
        }
    }

    /** 保存草稿(内容可反复覆盖,不改状态)。 */
    @Transactional
    public void saveDraft(long orgId, long taskId, String draftContent, long userId) {
        require(draftContent != null && !draftContent.isBlank(), CODE_PARAM, "service.param.invalid");
        Map<String, Object> task = find(orgId, taskId);
        require(((Number) task.get("status")).intValue() == ST_PENDING,
                CODE_BAD_STATE, "service.visit.badState");
        jdbc.update("update service_visit_task set draft_content = ? where id = ? and org_id = ?",
                draftContent.trim(), taskId, orgId);
    }

    /** 提交完成(0→1;rating 1-5+内容必填)。 */
    @Transactional
    public void finish(long orgId, long taskId, Integer rating, String content, long userId) {
        require(rating != null && rating >= 1 && rating <= 5, CODE_RATING_INVALID,
                "service.visit.ratingInvalid");
        require(content != null && !content.isBlank(), CODE_PARAM, "service.visit.contentRequired");
        Map<String, Object> task = find(orgId, taskId);
        require(((Number) task.get("status")).intValue() == ST_PENDING,
                CODE_BAD_STATE, "service.visit.badState");
        int updated = jdbc.update("""
                update service_visit_task set status = 1, rating = ?, content = ?, draft_content = null,
                    visitor_id = ?, visitor_name = ?, visit_time = now()
                where id = ? and org_id = ? and status = 0
                """, rating, content.trim(), userId, displayName(userId), taskId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, msg("service.visit.badState"));
        }
        bus.publish(new com.yingzao.addons.api.event.VisitFinishedEvent(taskId, orgId,
                ((Number) task.get("order_id")).longValue(), rating,
                ((Number) task.get("biz_id")) == null ? null
                        : ((Number) task.get("biz_id")).longValue(), userId),
                "service:visit:" + taskId + ":finished");
    }

    /** 标记无法完成(0→2;原因字典必填)。 */
    @Transactional
    public void fail(long orgId, long taskId, String reason, long userId) {
        require(reason != null && !reason.isBlank(), CODE_REASON_REQUIRED,
                "service.visit.reasonRequired");
        Map<String, Object> task = find(orgId, taskId);
        require(((Number) task.get("status")).intValue() == ST_PENDING,
                CODE_BAD_STATE, "service.visit.badState");
        int updated = jdbc.update("""
                update service_visit_task set status = 2, reason = ?, visitor_id = ?, visitor_name = ?,
                    visit_time = now() where id = ? and org_id = ? and status = 0
                """, reason.trim(), userId, displayName(userId), taskId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, msg("service.visit.badState"));
        }
    }

    public Map<String, Object> page(long orgId, Integer status, String bizType, String orderNo,
            int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (bizType != null && !bizType.isBlank()) {
            where.append(" and biz_type = ?");
            args.add(bizType.trim());
        }
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        Long total = jdbc.queryForObject("select count(*) from service_visit_task" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, order_no, customer_name, biz_type, biz_id, status, reason, rating,
                       content, draft_content, visitor_name, visit_time, plan_time, created_at,
                       (plan_time is not null and status = 0 and plan_time < now()) as overdue
                from service_visit_task""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> find(long orgId, long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from service_visit_task where id = ? and org_id = ?", taskId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("service.visit.notFound"));
        }
        return rows.get(0);
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
