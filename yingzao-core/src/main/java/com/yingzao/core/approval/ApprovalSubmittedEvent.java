package com.yingzao.core.approval;

/**
 * 审批已提交(经内核事件总线发布,outbox 与业务同事务):内核 ApprovalNotifyConsumer 消费落站内信;
 * 业务插件一般无需关心,关注办结结果请消费 {@link ApprovalFinishedEvent}。
 */
public record ApprovalSubmittedEvent(
        long approvalId,
        long orgId,
        String title,
        String bizAddon,
        String bizType,
        String bizId,
        long assigneeId) {
}
