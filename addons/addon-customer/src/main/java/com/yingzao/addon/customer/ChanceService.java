package com.yingzao.addon.customer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.LeadAssignedEvent;
import com.yingzao.core.web.BizException;

/**
 * 客源机会服务(V2):分配 / 列表 / 确认 / 完成。
 * 分配 = lead 前置校验 + 唯一机会约束 + 策略解析责任人,单事务内发布 LeadAssignedEvent;
 * 状态机 0 已分配 → 3 已确认 → 4 已完成(确认不可跳步);每客源生命周期内唯一机会。
 */
@Service
public class ChanceService {

    public static final int CODE_CHANCE_EXISTS = 210003;
    public static final int CODE_ILLEGAL_TRANSITION = 210005;
    public static final int CODE_MODE_UNSUPPORTED = 210007;
    public static final int CODE_CHANCE_NOT_FOUND = 210008;

    public static final int STATUS_ASSIGNED = 0;
    public static final int STATUS_CONFIRMED = 3;
    public static final int STATUS_COMPLETED = 4;

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final MessageSource messages;
    private final Map<String, LeadAssignStrategy> strategies;

    public ChanceService(JdbcTemplate jdbc, ApplicationEventPublisher publisher, MessageSource messages,
            List<LeadAssignStrategy> strategyList) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.messages = messages;
        this.strategies = strategyList.stream()
                .collect(Collectors.toUnmodifiableMap(LeadAssignStrategy::mode, s -> s));
    }

    @Transactional
    public long assign(long orgId, long leadId, String mode, Long assigneeId) {
        LeadAssignStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new BizException(CODE_MODE_UNSUPPORTED,
                    messages.getMessage("chance.mode.unsupported", null, LocaleContextHolder.getLocale()));
        }
        List<Map<String, Object>> leads = jdbc.queryForList(
                "select id, status from customer_lead where id = ? and org_id = ? limit 1", leadId, orgId);
        if (leads.isEmpty()) {
            throw new BizException(LeadService.CODE_LEAD_NOT_FOUND,
                    messages.getMessage("lead.notfound", null, LocaleContextHolder.getLocale()));
        }
        if (((Number) leads.get(0).get("status")).intValue() != LeadService.STATUS_INITIAL) {
            throw new BizException(LeadService.CODE_LEAD_ALREADY_CONVERTED,
                    messages.getMessage("lead.alreadyConverted", null, LocaleContextHolder.getLocale()));
        }
        Integer exists = jdbc.queryForObject("select count(*) from customer_chance where lead_id = ?",
                Integer.class, leadId);
        if (exists != null && exists > 0) {
            throw new BizException(CODE_CHANCE_EXISTS,
                    messages.getMessage("chance.exists", null, LocaleContextHolder.getLocale()));
        }
        long resolvedAssignee = strategy.resolveAssignee(new LeadAssignStrategy.AssignContext(orgId, leadId, assigneeId));
        jdbc.update("insert into customer_chance(org_id, lead_id, assignee_id, status) values (?,?,?,?)",
                orgId, leadId, resolvedAssignee, STATUS_ASSIGNED);
        long chanceId = jdbc.queryForObject("select last_insert_id()", Long.class);
        publisher.publishEvent(new LeadAssignedEvent(chanceId, leadId, orgId, resolvedAssignee, mode));
        return chanceId;
    }

    public List<Map<String, Object>> list(long orgId) {
        return jdbc.queryForList("""
                select c.id, c.lead_id, l.name as lead_name, l.phone, c.assignee_id,
                       u.display_name as assignee_name, c.status,
                       c.assigned_at, c.confirmed_at, c.completed_at
                from customer_chance c
                join customer_lead l on l.id = c.lead_id
                join yz_user u on u.id = c.assignee_id
                where c.org_id = ?
                order by c.id desc
                """, orgId);
    }

    @Transactional
    public void confirm(long orgId, long chanceId) {
        transition(orgId, chanceId, STATUS_ASSIGNED, STATUS_CONFIRMED, "confirmed_at");
    }

    @Transactional
    public void complete(long orgId, long chanceId) {
        transition(orgId, chanceId, STATUS_CONFIRMED, STATUS_COMPLETED, "completed_at");
    }

    private void transition(long orgId, long chanceId, int from, int to, String stampColumn) {
        int updated = jdbc.update(
                "update customer_chance set status = ?, " + stampColumn + " = now() where id = ? and org_id = ? and status = ?",
                to, chanceId, orgId, from);
        if (updated == 0) {
            Integer count = jdbc.queryForObject("select count(*) from customer_chance where id = ? and org_id = ?",
                    Integer.class, chanceId, orgId);
            if (count == null || count == 0) {
                throw new BizException(CODE_CHANCE_NOT_FOUND,
                        messages.getMessage("chance.notfound", null, LocaleContextHolder.getLocale()));
            }
            throw new BizException(CODE_ILLEGAL_TRANSITION,
                    messages.getMessage("chance.illegalTransition", null, LocaleContextHolder.getLocale()));
        }
    }
}
