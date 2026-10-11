package com.yingzao.core.approval;

/**
 * 审批已提交(事务提交后发布):内核通知服务监听落站内信;
 * 业务插件一般无需关心,关注办结结果请监听 {@link ApprovalFinishedEvent}。
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
