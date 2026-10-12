package com.yingzao.addon.sales;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 退货审批办结回写消费器(内核事件总线 SPI),biz 三元组 = (sales, saleRefund, 退货单id)。
 * 通过 → 退货单生效+主单轴 2 全退/3 倒单;驳回/撤回 → 退货单置无效+发起时预扣的销售明细 num 回加。
 * 取代原 SalesRefundApprovalListener 的 Spring AFTER_COMMIT 进程内监听(第 39 次运行迁总线):
 * 事务原子性由 SalesRefundService.onApprovalFinished 自身的 @Transactional 保证(消费器无事务上下文,
 * 进方法即新建,语义等价旧 REQUIRES_NEW);重放守卫内置(updated==0 即 return),失败由总线重试直至死信可见。
 */
@Component
public class SalesRefundApprovalConsumer implements YzEventConsumer {

    private final SalesRefundService refunds;

    public SalesRefundApprovalConsumer(@Lazy SalesRefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!SalesOrderService.BIZ_ADDON.equals(payload.bizAddon())
                || !SalesRefundService.BIZ_TYPE_REFUND.equals(payload.bizType())) {
            return;
        }
        long refundId;
        try {
            refundId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            return;
        }
        refunds.onApprovalFinished(payload.orgId(), refundId,
                payload.result() == ApprovalFinishedEvent.RESULT_APPROVED, payload.operatorId());
    }
}
