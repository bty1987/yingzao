package com.yingzao.addon.service;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.web.BizException;

/**
 * 售后域共享支撑:装修单契约快照(禁直查 decoration 表)+ 组织配置懒初始化。
 * 三域(投诉/回访/保修)共用;config 懒初始化=首次读取无行用默认并落行(与 quotation_config 同范式)。
 */
@Service
public class ServiceSupport {

    public static final int CODE_PARAM = 380001;
    public static final int CODE_ORDER_NOT_FOUND = 380006;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final DecorationApi decorations;

    public ServiceSupport(JdbcTemplate jdbc, MessageSource messages, DecorationApi decorations) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.decorations = decorations;
    }

    /** 装修单契约快照(orderId/orderNo/customerName);不存在 380006。 */
    public Map<String, Object> orderSnapshot(long orgId, Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BizException(CODE_PARAM, msg("service.param.invalid"));
        }
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_ORDER_NOT_FOUND, msg("service.complaint.orderNotFound")));
        return Map.of("orderId", order.orderId(), "orderNo", order.orderNo(),
                "customerName", order.customerName());
    }

    /** 组织配置(懒初始化)。 */
    @Transactional
    public Map<String, Object> config(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from service_config where org_id = ?", orgId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        jdbc.update("insert ignore into service_config(org_id) values (?)", orgId);
        return jdbc.queryForList("select * from service_config where org_id = ?", orgId).get(0);
    }

    public boolean visitOnComplaint(long orgId) {
        return asInt(config(orgId).get("visit_on_complaint")) == 1;
    }

    public boolean visitOnAcceptance(long orgId) {
        return asInt(config(orgId).get("visit_on_acceptance")) == 1;
    }

    public int acceptanceVisitDays(long orgId) {
        return asInt(config(orgId).get("acceptance_visit_days"));
    }

    public int warrantyMonths(long orgId) {
        return asInt(config(orgId).get("warranty_months"));
    }

    /** 配置更新命令(null=保留现值)。 */
    public record ConfigUpdate(Integer visitOnComplaint, Integer visitOnAcceptance,
            Integer acceptanceVisitDays, Integer warrantyMonths) {
    }

    @Transactional
    public void updateConfig(long orgId, ConfigUpdate cmd) {
        config(orgId);
        if (cmd == null) {
            return;
        }
        if (cmd.acceptanceVisitDays() != null) {
            require(cmd.acceptanceVisitDays() >= 1 && cmd.acceptanceVisitDays() <= 365, 380008,
                    "service.config.daysInvalid");
        }
        if (cmd.warrantyMonths() != null) {
            require(cmd.warrantyMonths() >= 1 && cmd.warrantyMonths() <= 120, 380008,
                    "service.config.warrantyInvalid");
        }
        jdbc.update("""
                update service_config set visit_on_complaint = ?, visit_on_acceptance = ?,
                    acceptance_visit_days = ?, warranty_months = ? where org_id = ?
                """, cmd.visitOnComplaint() == null ? 1 : cmd.visitOnComplaint(),
                cmd.visitOnAcceptance() == null ? 0 : cmd.visitOnAcceptance(),
                cmd.acceptanceVisitDays() == null ? 7 : cmd.acceptanceVisitDays(),
                cmd.warrantyMonths() == null ? 24 : cmd.warrantyMonths(), orgId);
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private static int asInt(Object v) {
        return ((Number) v).intValue();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
