package com.yingzao.addon.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 保修登记域(老系统零规则,全新定义——rules/construction §15-6 落地):
 * 保修期=起点(start_time)+月数(months),查询期计算不落到期列;
 * phase 徽章:0 在保/1 即将到期(30 天内)/2 已过保/3 已作废(查询列);
 * append-only:只插不改,作废=状态位;有效行 uk 查重(org+order 一单一保,作废后可重登);
 * 起点默认竣工验收日(前端传,补录可改——起点的权威源在 construction)。
 */
@Service
public class GuaranteeService {

    public static final int CODE_PARAM = 380001;
    public static final int CODE_NOT_FOUND = 380002;
    public static final int CODE_BAD_STATE = 380003;
    public static final int CODE_EXISTS = 380007;

    public static final int ST_ACTIVE = 0;
    public static final int ST_VOID = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final ServiceSupport support;

    public GuaranteeService(JdbcTemplate jdbc, MessageSource messages, ServiceSupport support) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.support = support;
    }

    public record CreateCmd(Long orderId, String startTime, Integer months, String remark) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, long userId) {
        if (cmd == null || cmd.startTime() == null || cmd.startTime().isBlank()) {
            throw new BizException(CODE_PARAM, msg("service.param.invalid"));
        }
        int months = cmd.months() == null ? support.warrantyMonths(orgId) : cmd.months();
        require(months >= 1 && months <= 120, CODE_PARAM, "service.param.invalid");
        Map<String, Object> order = support.orderSnapshot(orgId, cmd.orderId());
        Integer exists = jdbc.queryForObject(
                "select count(*) from service_guarantee where org_id = ? and order_id = ? and status = 0",
                Integer.class, orgId, cmd.orderId());
        if (exists != null && exists > 0) {
            throw new BizException(CODE_EXISTS, msg("service.guarantee.exists"));
        }
        jdbc.update("""
                insert into service_guarantee(org_id, order_id, order_no, customer_name, start_time,
                    months, status, remark, created_by)
                values (?,?,?,?,?,?,0,?,?)
                """, orgId, order.get("orderId"), order.get("orderNo"), order.get("customerName"),
                cmd.startTime().trim(), months, blankToNull(cmd.remark()), userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 作废(0→1;作废后可重新登记——查重只看有效行)。 */
    @Transactional
    public void cancel(long orgId, long guaranteeId, long userId) {
        int updated = jdbc.update(
                "update service_guarantee set status = 1 where id = ? and org_id = ? and status = 0",
                guaranteeId, orgId);
        if (updated == 0) {
            find(orgId, guaranteeId);
            throw new BizException(CODE_BAD_STATE, msg("service.guarantee.notFound"));
        }
    }

    /**
     * 分页(phase 过滤基于查询期计算列,走派生表):
     * phase 0 在保/1 即将到期(30 天内)/2 已过保/3 已作废。
     */
    public Map<String, Object> page(long orgId, Integer phase, String orderNo, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(
                " from service_guarantee g where g.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and g.order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        String baseSql = """
                select g.id, g.order_id, g.order_no, g.customer_name, g.start_time, g.months, g.status,
                       g.remark, g.created_at,
                       date_add(g.start_time, interval g.months month) as expire_time,
                       case when g.status = 1 then 3
                            when date_add(g.start_time, interval g.months month) < now() then 2
                            when date_add(g.start_time, interval g.months month)
                                 < date_add(now(), interval 30 day) then 1
                            else 0 end as phase
                """ + where;
        StringBuilder having = new StringBuilder();
        List<Object> allArgs = new ArrayList<>(args);
        if (phase != null) {
            having.append(" having phase = ?");
            allArgs.add(phase);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from (" + baseSql + having + ") t", Long.class, allArgs.toArray());
        List<Object> pageArgs = new ArrayList<>(allArgs);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from (" + baseSql + having + ") t order by t.id desc limit ? offset ?",
                pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> find(long orgId, long guaranteeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from service_guarantee where id = ? and org_id = ?", guaranteeId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("service.guarantee.notFound"));
        }
        return rows.get(0);
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
