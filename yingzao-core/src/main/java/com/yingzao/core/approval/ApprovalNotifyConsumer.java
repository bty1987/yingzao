package com.yingzao.core.approval;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 审批站内通知消费器(内核自消费):提交→通知处理人,转交→通知新处理人,办结→通知发起人(撤回→通知原处理人)。
 * 取代原 ApprovalNotifier 的 Spring AFTER_COMMIT 进程内监听(第 39 次运行迁总线,事件总线迁移第二波):
 * 通知失败不再 log.warn 一行了事,总线退避重试直至消费确认,超限死信 /api/events 管理页可见可 requeue。
 * 审批是内核平台设施,三类事件均为内核自有事件,内核自消费不违反「内核不订阅业务事件」红线(RFC §6.7)。
 * 幂等守卫(对齐 addon-customer LeadAssignedConsumer 姿势):按 source_addon+source_type+source_id 查重——
 * submitted/finished 天然一单一次;transferred 的 source_id 用 approvalId:toUserId 复合,
 * 同一审批重复转给同一人不重复通知,转给不同人各自通知。
 */
@Component
public class ApprovalNotifyConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifyConsumer.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final MessageSource messages;

    public ApprovalNotifyConsumer(JdbcTemplate jdbc, NotificationService notifications, MessageSource messages) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalSubmittedEvent", "ApprovalTransferredEvent", "ApprovalFinishedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "ApprovalSubmittedEvent" -> onSubmitted(event.payloadAs(ApprovalSubmittedEvent.class));
            case "ApprovalTransferredEvent" -> onTransferred(event.payloadAs(ApprovalTransferredEvent.class));
            case "ApprovalFinishedEvent" -> onFinished(event.payloadAs(ApprovalFinishedEvent.class));
            default -> log.warn("unexpected event type {} for approval notify consumer", event.eventType());
        }
    }

    private void onSubmitted(ApprovalSubmittedEvent event) {
        if (notified(event.bizAddon(), "approval.submitted", String.valueOf(event.approvalId()))) {
            return;
        }
        String title = messages.getMessage("approval.notify.submitted",
                new Object[] { event.title() }, LocaleContextHolder.getLocale());
        notifications.send(new NotificationService.SendCmd(event.orgId(), event.assigneeId(),
                title, null, event.bizAddon(), "approval.submitted", String.valueOf(event.approvalId())));
    }

    private void onTransferred(ApprovalTransferredEvent event) {
        String sourceId = event.approvalId() + ":" + event.toUserId();
        if (notified(event.bizAddon(), "approval.transferred", sourceId)) {
            return;
        }
        String operatorName = jdbc.queryForObject(
                "select display_name from yz_user where id = ?", String.class, event.operatorId());
        String title = messages.getMessage("approval.notify.transferred",
                new Object[] { operatorName, event.title() }, LocaleContextHolder.getLocale());
        notifications.send(new NotificationService.SendCmd(event.orgId(), event.toUserId(),
                title, null, event.bizAddon(), "approval.transferred", sourceId));
    }

    private void onFinished(ApprovalFinishedEvent event) {
        if (notified(event.bizAddon(), "approval.finished", String.valueOf(event.approvalId()))) {
            return;
        }
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
    }

    /** 幂等守卫:同一插件+同一通知类型+同一业务锚点只通知一次(重试/requeue 重放直接跳过)。 */
    private boolean notified(String sourceAddon, String sourceType, String sourceId) {
        Integer dup = jdbc.queryForObject("""
                select count(*) from yz_notification
                where source_addon = ? and source_type = ? and source_id = ?
                """, Integer.class, sourceAddon, sourceType, sourceId);
        return dup != null && dup > 0;
    }
}
