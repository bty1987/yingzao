package com.yingzao.addon.inventory;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.web.BizException;

/**
 * 领料审批办结消费器(内核事件总线 SPI,A2):只处理 biz 三元组 =
 * (inventory, requisition, 领料单id)。通过 → 7 审核中回 0 初始(可预出/出库);
 * 驳回/撤回 → 置 4 已拒绝 + 普通单对称 refund 工程额度(审批期已 consume)。
 * TransferApprovalConsumer 同构:事务由 onApprovalFinished 自身 @Transactional 保证;
 * BizException(状态守卫失配)吞掉 log.error 不重试,基础设施异常上抛交总线重试。
 */
@Component
public class RequisitionApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RequisitionApprovalConsumer.class);

    private final RequisitionService requisitions;

    public RequisitionApprovalConsumer(@Lazy RequisitionService requisitions) {
        this.requisitions = requisitions;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!RequisitionService.BIZ_ADDON.equals(payload.bizAddon())
                || !RequisitionService.BIZ_TYPE_REQUISITION.equals(payload.bizType())) {
            return;
        }
        long requisitionId;
        try {
            requisitionId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.error("requisition approval finished with unparsable bizId={}, approvalId={}",
                    payload.bizId(), payload.approvalId());
            return;
        }
        try {
            requisitions.onApprovalFinished(payload.orgId(), requisitionId, payload.approvalId(),
                    payload.result() == ApprovalFinishedEvent.RESULT_APPROVED, payload.operatorId());
        } catch (BizException e) {
            log.error("requisition approval {} finish refused for requisition {}: {}",
                    payload.approvalId(), requisitionId, e.getMessage());
        }
    }
}
