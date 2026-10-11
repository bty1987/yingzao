package com.yingzao.addon.sales;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.core.approval.ApprovalFinishedEvent;

/**
 * 退货审批办结回写:监听内核审批办结事件(事务提交后发布;军规 8:REQUIRES_NEW 自起事务),
 * biz 三元组 = (sales, saleRefund, 退货单id)。通过 → 退货单生效+主单轴 2 全退/3 倒单;
 * 驳回/撤回 → 退货单置无效+发起时预扣的销售明细 num 回加。
 */
@Component
public class SalesRefundApprovalListener {

    private final SalesRefundService refunds;

    public SalesRefundApprovalListener(SalesRefundService refunds) {
        this.refunds = refunds;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void onFinished(ApprovalFinishedEvent event) {
        if (!SalesOrderService.BIZ_ADDON.equals(event.bizAddon())
                || !SalesRefundService.BIZ_TYPE_REFUND.equals(event.bizType())) {
            return;
        }
        long refundId;
        try {
            refundId = Long.parseLong(event.bizId());
        } catch (NumberFormatException e) {
            return;
        }
        refunds.onApprovalFinished(event.orgId(), refundId,
                event.result() == ApprovalFinishedEvent.RESULT_APPROVED, event.operatorId());
    }
}
