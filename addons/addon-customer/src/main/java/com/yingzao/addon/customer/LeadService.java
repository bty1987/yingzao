package com.yingzao.addon.customer;

import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 客源服务:登记 / 列表 / 转化。
 * 转化在事务内更新状态并发布 LeadConvertedEvent,客户档案 addon 于事务提交后建档。
 */
@Service
public class LeadService {

    public static final int CODE_LEAD_NOT_FOUND = 210001;
    public static final int CODE_LEAD_ALREADY_CONVERTED = 210002;

    public static final int STATUS_INITIAL = 0;
    public static final int STATUS_CONVERTED = 1;

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final MessageSource messages;

    public LeadService(JdbcTemplate jdbc, ApplicationEventPublisher publisher, MessageSource messages) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.messages = messages;
    }

    public Long create(long orgId, String name, String phone, String source, String note) {
        jdbc.update("insert into customer_lead(org_id, name, phone, source, note, status) values (?,?,?,?,?,0)",
                orgId, name, phone, source, note);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    public List<Map<String, Object>> list(long orgId) {
        return jdbc.queryForList(
                "select id, name, phone, source, note, status, created_at from customer_lead where org_id = ? order by id desc",
                orgId);
    }

    @Transactional
    public void convert(long orgId, long leadId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, phone, status from customer_lead where id = ? and org_id = ? limit 1", leadId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_LEAD_NOT_FOUND,
                    messages.getMessage("lead.notfound", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> lead = rows.get(0);
        if (((Number) lead.get("status")).intValue() != STATUS_INITIAL) {
            throw new BizException(CODE_LEAD_ALREADY_CONVERTED,
                    messages.getMessage("lead.alreadyConverted", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("update customer_lead set status = 1 where id = ?", leadId);
        publisher.publishEvent(new com.yingzao.addons.api.event.LeadConvertedEvent(leadId, orgId,
                (String) lead.get("name"), (String) lead.get("phone")));
    }
}
