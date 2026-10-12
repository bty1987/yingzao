package com.yingzao.addon.decoration;

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
 * 终止审批办结消费器(内核事件总线 SPI):只处理 biz 三元组 = (decoration, termination, 装修单id)。
 * 通过 → 合同线 3 签约 → 6 终止(带 approval_id 守卫,陈旧事件拒),联动未收款项单作废+归属置失效;
 * 驳回/撤回 → 主单零改动(修老「驳回顶态」缺陷;termination_approval_id 保留最近一次审批留痕,再提交即覆盖)。
 * 取代原 TerminationApprovalListener 的 Spring AFTER_COMMIT 进程内监听(第 39 次运行迁总线):
 * 事务原子性由 DecorationService.executeTermination 自身的 @Transactional 保证(消费器无事务上下文,
 * 进方法即新建,语义等价旧 REQUIRES_NEW);异常分两类——BizException(守卫失配/状态已迁移,重放不会变好)
 * 吞掉 log.error 留痕,其他异常(DB 故障等基础设施失败)上抛交总线退避重试,不再「异常即丢」。
 */
@Component
public class TerminationApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TerminationApprovalConsumer.class);

    private final DecorationService orders;

    public TerminationApprovalConsumer(@Lazy DecorationService orders) {
        this.orders = orders;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!DecorationService.BIZ_ADDON.equals(payload.bizAddon())
                || !DecorationService.BIZ_TYPE_TERMINATION.equals(payload.bizType())) {
            return;
        }
        long orderId;
        try {
            orderId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.error("termination approval finished with unparsable bizId={}, approvalId={}",
                    payload.bizId(), payload.approvalId());
            return;
        }
        if (payload.result() != ApprovalFinishedEvent.RESULT_APPROVED) {
            log.info("termination approval {} rejected/canceled, order {} kept untouched",
                    payload.approvalId(), orderId);
            return;
        }
        try {
            orders.executeTermination(payload.orgId(), orderId, payload.approvalId(), payload.operatorId());
        } catch (BizException e) {
            // 守卫失配/并发终态:业务性拒绝,重放不会变好,log.error 可见(禁静默红线),不重试
            log.error("termination approval {} execute refused for order {}: {}",
                    payload.approvalId(), orderId, e.getMessage());
        }
    }
}
