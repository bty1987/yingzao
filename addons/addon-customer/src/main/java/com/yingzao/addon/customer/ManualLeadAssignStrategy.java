package com.yingzao.addon.customer;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.web.BizException;

/**
 * 手动分配:调用方显式指定责任人,校验其存在且属于同一组织。
 */
@Component
public class ManualLeadAssignStrategy implements LeadAssignStrategy {

    public static final int CODE_ASSIGNEE_NOT_FOUND = 210004;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public ManualLeadAssignStrategy(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    @Override
    public String mode() {
        return "manual";
    }

    @Override
    public long resolveAssignee(AssignContext context) {
        if (context.assigneeId() == null) {
            throw new BizException(CODE_ASSIGNEE_NOT_FOUND,
                    messages.getMessage("chance.assignee.required", null, LocaleContextHolder.getLocale()));
        }
        Integer count = jdbc.queryForObject("select count(*) from yz_user where id = ? and org_id = ?",
                Integer.class, context.assigneeId(), context.orgId());
        if (count == null || count == 0) {
            throw new BizException(CODE_ASSIGNEE_NOT_FOUND,
                    messages.getMessage("chance.assignee.notfound", null, LocaleContextHolder.getLocale()));
        }
        return context.assigneeId();
    }
}
