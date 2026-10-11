package com.yingzao.addon.decoration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.core.approval.ApprovalFinishedEvent;

/**
 * 终止审批办结回写:监听内核 ApprovalFinishedEvent,只处理 biz 三元组 = (decoration, termination, 装修单id)。
 * 通过 → 合同线 3 签约 → 6 终止(带 approval_id 守卫,陈旧事件拒),联动未收款项单作废+归属置失效;
 * 驳回/撤回 → 主单零改动(修老「驳回顶态」缺陷;termination_approval_id 保留最近一次审批留痕,再提交即覆盖)。
 * 军规 8:AFTER_COMMIT 上的 @Transactional 必须 REQUIRES_NEW。
 */
@Component
public class TerminationApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(TerminationApprovalListener.class);

    private final DecorationService orders;

    public TerminationApprovalListener(DecorationService orders) {
        this.orders = orders;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApprovalFinished(ApprovalFinishedEvent event) {
        if (!DecorationService.BIZ_ADDON.equals(event.bizAddon())
                || !DecorationService.BIZ_TYPE_TERMINATION.equals(event.bizType())) {
            return;
        }
        long orderId;
        try {
            orderId = Long.parseLong(event.bizId());
        } catch (NumberFormatException e) {
            log.error("termination approval finished with unparsable bizId={}, approvalId={}",
                    event.bizId(), event.approvalId());
            return;
        }
        if (event.result() != ApprovalFinishedEvent.RESULT_APPROVED) {
            log.info("termination approval {} rejected/canceled, order {} kept untouched",
                    event.approvalId(), orderId);
            return;
        }
        try {
            orders.executeTermination(event.orgId(), orderId, event.approvalId(), event.operatorId());
        } catch (Exception e) {
            // 主事务早已提交;守卫失配/并发终态一律 log.error 可见(禁静默红线),不回滚他人
            log.error("termination approval {} execute failed for order {}: {}",
                    event.approvalId(), orderId, e.getMessage());
        }
    }
}
