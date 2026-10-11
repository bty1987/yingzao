package com.yingzao.core.approval;

/**
 * 审批办结(事务提交后发布):业务插件监听本事件回写自身业务状态
 * (如装修单过渡态 20/30/32/33 的解除)。biz_* 三元组为提交时的溯源透传,内核不解释其含义。
 * result 取值与审批单终态一致:1 通过 / 2 驳回 / 3 撤回。
 */
public record ApprovalFinishedEvent(
        long approvalId,
        long orgId,
        String title,
        String bizAddon,
        String bizType,
        String bizId,
        long applicantId,
        long assigneeId,
        long operatorId,
        int result) {

    public static final int RESULT_APPROVED = 1;
    public static final int RESULT_REJECTED = 2;
    public static final int RESULT_CANCELED = 3;
}
