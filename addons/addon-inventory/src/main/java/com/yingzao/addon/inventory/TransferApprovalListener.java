package com.yingzao.addon.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.core.approval.ApprovalFinishedEvent;

/**
 * 调拨审批办结回写:监听内核 ApprovalFinishedEvent,只处理 biz 三元组 = (inventory, transfer, 调拨单id)。
 * 通过 → 双向过账(源仓出/目标仓入)+ 单据置已确认 + 发布库存事件;
 * 驳回/撤回 → 单据置已驳回(3),库存零改动(审批期不占库存,老语义保留)。
 * 军规 8:AFTER_COMMIT 上的 @Transactional 必须 REQUIRES_NEW;主事务已提交,失败 log.error 可见。
 */
@Component
public class TransferApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(TransferApprovalListener.class);

    private final ManifestService manifests;

    public TransferApprovalListener(ManifestService manifests) {
        this.manifests = manifests;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApprovalFinished(ApprovalFinishedEvent event) {
        if (!ManifestService.BIZ_ADDON.equals(event.bizAddon())
                || !ManifestService.BIZ_TYPE_TRANSFER.equals(event.bizType())) {
            return;
        }
        long manifestId;
        try {
            manifestId = Long.parseLong(event.bizId());
        } catch (NumberFormatException e) {
            log.error("transfer approval finished with unparsable bizId={}, approvalId={}",
                    event.bizId(), event.approvalId());
            return;
        }
        try {
            manifests.executeTransfer(event.orgId(), manifestId, event.approvalId(),
                    event.result() == ApprovalFinishedEvent.RESULT_APPROVED,
                    event.operatorId());
        } catch (Exception e) {
            log.error("transfer approval {} execute failed for manifest {}: {}",
                    event.approvalId(), manifestId, e.getMessage());
        }
    }
}
