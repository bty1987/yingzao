package com.yingzao.addon.customer;

import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.LeadAssignedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 客源分配消费器(内核事件总线 SPI):分配成功后给责任人落一条站内通知——
 * "插件消费领域事件 → 调用内核通知平台设施"的标准姿势,内核不认识业务事件。
 * 取代原 LeadAssignedNotifier 的 Spring AFTER_COMMIT 进程内监听(第 38 次运行迁总线):
 * 通知失败不再悄然丢失(旧姿势 System.err 一行了事),总线退避重试直至消费确认,死信管理端可见。
 * 幂等守卫:按 source_addon+source_type+source_id(=chanceId) 查重,同一机会只通知一次——
 * 重试/requeue 重放直接跳过,比"重放=多一条通知"更严谨;机会与通知天然一对一,守卫可靠。
 */
@Component
public class LeadAssignedConsumer implements YzEventConsumer {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final MessageSource messages;

    public LeadAssignedConsumer(JdbcTemplate jdbc, NotificationService notifications, MessageSource messages) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LeadAssignedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        LeadAssignedEvent payload = event.payloadAs(LeadAssignedEvent.class);
        Integer dup = jdbc.queryForObject("""
                select count(*) from yz_notification
                where source_addon = 'customer' and source_type = 'lead.assigned' and source_id = ?
                """, Integer.class, String.valueOf(payload.chanceId()));
        if (dup != null && dup > 0) {
            return;
        }
        String leadName = jdbc.queryForObject(
                "select name from customer_lead where id = ?", String.class, payload.leadId());
        var locale = LocaleContextHolder.getLocale();
        String title = messages.getMessage("chance.notify.title", null, locale);
        String content = messages.getMessage("chance.notify.content",
                new Object[] { leadName, payload.mode() }, locale);
        notifications.send(new NotificationService.SendCmd(payload.orgId(), payload.assigneeId(),
                title, content, "customer", "lead.assigned", String.valueOf(payload.chanceId())));
    }
}
