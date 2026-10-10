package com.yingzao.addon.customer;

import java.util.List;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.web.BizException;

/**
 * 自动分配(spike 级轮询):候选池 = 本组织全部用户,选在途机会(status 0/3)最少者,
 * 同数取用户 id 最小保证确定性。老系统的设计师池规则引擎/数量配额(÷1000 语义已剔除)归 V3 规则模块。
 */
@Component
public class AutoRoundRobinLeadAssignStrategy implements LeadAssignStrategy {

    public static final int CODE_POOL_EMPTY = 210006;

    private static final String POOL_SQL = """
            select u.id
            from yz_user u
            where u.org_id = ?
            order by (select count(*) from customer_chance c
                      where c.assignee_id = u.id and c.status in (0, 3)) asc, u.id asc
            limit 1
            """;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public AutoRoundRobinLeadAssignStrategy(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    @Override
    public String mode() {
        return "auto";
    }

    @Override
    public long resolveAssignee(AssignContext context) {
        List<Long> ids = jdbc.queryForList(POOL_SQL, Long.class, context.orgId());
        if (ids.isEmpty()) {
            throw new BizException(CODE_POOL_EMPTY,
                    messages.getMessage("chance.pool.empty", null, LocaleContextHolder.getLocale()));
        }
        return ids.get(0);
    }
}
