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
 * 调拨审批办结消费器(内核事件总线 SPI):只处理 biz 三元组 = (inventory, transfer, 调拨单id)。
 * 通过 → 双向过账(源仓出/目标仓入)+ 单据置已确认 + 发布库存事件;
 * 驳回/撤回 → 单据置已驳回(3),库存零改动(审批期不占库存,老语义保留)。
 * 取代原 TransferApprovalListener 的 Spring AFTER_COMMIT 进程内监听(第 39 次运行迁总线):
 * 事务原子性由 ManifestService.executeTransfer 自身的 @Transactional 保证(消费器无事务上下文,
 * 进方法即新建,语义等价旧 REQUIRES_NEW);异常分两类——BizException(状态守卫失配,重放不会变好)
 * 吞掉 log.error 留痕,其他异常(DB 故障等基础设施失败)上抛交总线退避重试,不再「异常即丢」。
 */
@Component
public class TransferApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferApprovalConsumer.class);

    private final ManifestService manifests;

    public TransferApprovalConsumer(@Lazy ManifestService manifests) {
        this.manifests = manifests;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!ManifestService.BIZ_ADDON.equals(payload.bizAddon())
                || !ManifestService.BIZ_TYPE_TRANSFER.equals(payload.bizType())) {
            return;
        }
        long manifestId;
        try {
            manifestId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.error("transfer approval finished with unparsable bizId={}, approvalId={}",
                    payload.bizId(), payload.approvalId());
            return;
        }
        try {
            manifests.executeTransfer(payload.orgId(), manifestId, payload.approvalId(),
                    payload.result() == ApprovalFinishedEvent.RESULT_APPROVED,
                    payload.operatorId());
        } catch (BizException e) {
            // 状态守卫失配(已确认/已驳回):业务性拒绝,重放不会变好,log.error 可见,不重试
            log.error("transfer approval {} execute refused for manifest {}: {}",
                    payload.approvalId(), manifestId, e.getMessage());
        }
    }
}
