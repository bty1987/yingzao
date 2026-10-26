package com.yingzao.addon.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.addons.api.event.ComplaintHandledEvent;
import com.yingzao.core.web.BizException;

/**
 * 投诉域(老 CustomerServiceComplaint 的状态机扩展):0 待处理→1 处理中→2 已处理;
 * 处理结果必填(老 handle 空意见不迁);type/level 字典单源(yml,老前端字典不迁);
 * 承诺时限超期=查询期计算不落列;处理完按组织开关联动生成回访任务(biz=complaint)。
 */
@Service
public class ComplaintService {

    public static final int CODE_PARAM = 380001;
    public static final int CODE_NOT_FOUND = 380002;
    public static final int CODE_BAD_STATE = 380003;
    public static final int CODE_RESULT_REQUIRED = 380004;

    public static final int ST_PENDING = 0;
    public static final int ST_HANDLING = 1;
    public static final int ST_HANDLED = 2;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;
    private final ServiceSupport support;
    private final VisitService visits;

    public ComplaintService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus,
            ServiceSupport support, @Lazy VisitService visits) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
        this.support = support;
        this.visits = visits;
    }

    public record CreateCmd(Long orderId, String type, String level, String content, String promiseTime) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, long userId) {
        if (cmd == null || cmd.type() == null || cmd.type().isBlank() || cmd.level() == null
                || cmd.level().isBlank() || cmd.content() == null || cmd.content().isBlank()) {
            throw new BizException(CODE_PARAM, msg("service.param.invalid"));
        }
        Map<String, Object> order = support.orderSnapshot(orgId, cmd.orderId());
        jdbc.update("""
                insert into service_complaint(org_id, order_id, order_no, customer_name, type, level,
                    content, status, promise_time, created_by)
                values (?,?,?,?,?,?,?,0,?,?)
                """, orgId, order.get("orderId"), order.get("orderNo"), order.get("customerName"),
                cmd.type().trim(), cmd.level().trim(), cmd.content().trim(),
                blankToNull(cmd.promiseTime()), userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 开始处理(0→1;条件更新,修老裸状态写)。 */
    @Transactional
    public void start(long orgId, long complaintId, long userId) {
        int updated = jdbc.update(
                "update service_complaint set status = 1, handler_id = ?, handler_name = ?"
                        + " where id = ? and org_id = ? and status = 0",
                userId, displayName(userId), complaintId, orgId);
        if (updated == 0) {
            throwBadState(orgId, complaintId);
        }
    }

    /** 标记已处理(→2;处理结果必填;联动开关开→生成回访任务 biz=complaint)。 */
    @Transactional
    public void handle(long orgId, long complaintId, String handleResult, long userId) {
        if (handleResult == null || handleResult.isBlank()) {
            throw new BizException(CODE_RESULT_REQUIRED, msg("service.complaint.resultRequired"));
        }
        Map<String, Object> complaint = find(orgId, complaintId);
        if (((Number) complaint.get("status")).intValue() == ST_HANDLED) {
            throw new BizException(CODE_BAD_STATE, msg("service.complaint.badState"));
        }
        int updated = jdbc.update(
                "update service_complaint set status = 2, handle_result = ?, handler_id = ?,"
                        + " handler_name = ? where id = ? and org_id = ? and status in (0, 1)",
                handleResult.trim(), userId, displayName(userId), complaintId, orgId);
        if (updated == 0) {
            throwBadState(orgId, complaintId);
        }
        if (support.visitOnComplaint(orgId)) {
            visits.createFromLink(orgId, "complaint", complaintId,
                    ((Number) complaint.get("order_id")).longValue(), userId);
        }
        bus.publish(new ComplaintHandledEvent(complaintId, orgId,
                ((Number) complaint.get("order_id")).longValue(), userId),
                "service:complaint:" + complaintId + ":handled");
    }

    public Map<String, Object> page(long orgId, Integer status, String orderNo, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        Long total = jdbc.queryForObject("select count(*) from service_complaint" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, order_no, customer_name, type, level, content, handle_result,
                       handler_name, status, promise_time, created_at,
                       (promise_time is not null and status < 2 and promise_time < now()) as overdue
                from service_complaint""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> find(long orgId, long complaintId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from service_complaint where id = ? and org_id = ?", complaintId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("service.complaint.notFound"));
        }
        return rows.get(0);
    }

    private void throwBadState(long orgId, long complaintId) {
        find(orgId, complaintId);
        throw new BizException(CODE_BAD_STATE, msg("service.complaint.badState"));
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
