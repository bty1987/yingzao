package com.yingzao.core.approval;

/**
 * 审批转交(经内核事件总线发布,outbox 与业务同事务):处理人变更,审批状态不变。
 */
public record ApprovalTransferredEvent(
        long approvalId,
        long orgId,
        String title,
        String bizAddon,
        String bizType,
        String bizId,
        long toUserId,
        long operatorId) {
}
