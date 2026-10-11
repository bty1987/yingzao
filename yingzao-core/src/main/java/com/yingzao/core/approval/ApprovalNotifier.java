package com.yingzao.core.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.core.notification.NotificationService;

/**
 * 审批站内通知(内核自消费):提交→通知处理人,转交→通知新处理人,办结→通知发起人(撤回→通知原处理人)。
 * 与插件侧 LeadAssignedNotifier 同一姿势:AFTER_COMMIT 监听 + try-catch 吞异常,
 * 通知属于尽力而为,绝不影响已提交的审批主流程。
 */
@Component
public class ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final MessageSource messages;

    public ApprovalNotifier(JdbcTemplate jdbc, NotificationService notifications, MessageSource messages) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.messages = messages;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(ApprovalSubmittedEvent event) {
        try {
            String title = messages.getMessage("approval.notify.submitted",
                    new Object[] { event.title() }, LocaleContextHolder.getLocale());
            notifications.send(new NotificationService.SendCmd(event.orgId(), event.assigneeId(),
                    title, null, event.bizAddon(), "approval.submitted", String.valueOf(event.approvalId())));
        } catch (Exception e) {
            log.warn("approval submitted notification failed, approvalId={}", event.approvalId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferred(ApprovalTransferredEvent event) {
        try {
            String operatorName = queryName(event.operatorId());
            String title = messages.getMessage("approval.notify.transferred",
                    new Object[] { operatorName, event.title() }, LocaleContextHolder.getLocale());
            notifications.send(new NotificationService.SendCmd(event.orgId(), event.toUserId(),
                    title, null, event.bizAddon(), "approval.transferred", String.valueOf(event.approvalId())));
        } catch (Exception e) {
            log.warn("approval transferred notification failed, approvalId={}", event.approvalId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinished(ApprovalFinishedEvent event) {
        try {
            String key = switch (event.result()) {
                case ApprovalFinishedEvent.RESULT_APPROVED -> "approval.notify.approved";
                case ApprovalFinishedEvent.RESULT_REJECTED -> "approval.notify.rejected";
                default -> "approval.notify.canceled";
            };
            // 通过/驳回通知发起人;撤回通知原处理人(其待办随之清除)
            long receiver = event.result() == ApprovalFinishedEvent.RESULT_CANCELED
                    ? event.assigneeId()
                    : event.applicantId();
            String title = messages.getMessage(key,
                    new Object[] { event.title() }, LocaleContextHolder.getLocale());
            notifications.send(new NotificationService.SendCmd(event.orgId(), receiver,
                    title, null, event.bizAddon(), "approval.finished", String.valueOf(event.approvalId())));
        } catch (Exception e) {
            log.warn("approval finished notification failed, approvalId={}", event.approvalId(), e);
        }
    }

    private String queryName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }
}
