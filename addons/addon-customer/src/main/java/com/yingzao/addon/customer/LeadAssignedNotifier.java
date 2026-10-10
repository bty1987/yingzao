package com.yingzao.addon.customer;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.addons.api.event.LeadAssignedEvent;
import com.yingzao.core.notification.NotificationService;

/**
 * 客源分配通知订阅方:分配事务提交后(AFTER_COMMIT)给责任人落一条站内通知。
 * 演示"插件消费领域事件 → 调用内核通知平台设施"的标准姿势——内核不认识业务事件;
 * 通知失败只记日志,绝不影响已提交的分配主流程。
 */
@Component
public class LeadAssignedNotifier {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final MessageSource messages;

    public LeadAssignedNotifier(JdbcTemplate jdbc, NotificationService notifications, MessageSource messages) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.messages = messages;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLeadAssigned(LeadAssignedEvent event) {
        try {
            String leadName = jdbc.queryForObject(
                    "select name from customer_lead where id = ?", String.class, event.leadId());
            var locale = LocaleContextHolder.getLocale();
            String title = messages.getMessage("chance.notify.title", null, locale);
            String content = messages.getMessage("chance.notify.content",
                    new Object[] { leadName, event.mode() }, locale);
            notifications.send(new NotificationService.SendCmd(event.orgId(), event.assigneeId(),
                    title, content, "customer", "lead.assigned", String.valueOf(event.chanceId())));
        } catch (Exception e) {
            // 主事务已提交,通知属于尽力而为;异常上抛只会把成功的分配变成 500
            System.err.println("[customer] lead assigned notification failed, chanceId="
                    + event.chanceId() + " : " + e.getMessage());
        }
    }
}
