package com.yingzao.addon.member;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentConfirmedEvent;
import com.yingzao.addons.api.event.PaymentRefundedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 收款事件 → 积分记账消费器(总线 SPI)。
 * 消费 PaymentConfirmedEvent 记分 / PaymentRefundedEvent 退款冲减;
 * orgId 从事件载荷取(消费线程无 YzContext)。幂等:member_point_record 幂等锚
 * uk(org,src_addon,src_type,src_id,type) + 服务层先查后插,总线重放不重复记账。
 * 异常分层:IllegalStateException(默认活动缺失/客户不可解/账务异常)= 可修复或需人工对账,
 * 上抛交总线退避重试、超限死信可见,运营修复后 requeue 补记——积分丢失不可接受
 * (information.md「44% 事件失败无重试」红线)。
 */
@Component
public class PaymentPointConsumer implements YzEventConsumer {

    private final MemberPointService points;

    public PaymentPointConsumer(@Lazy MemberPointService points) {
        this.points = points;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentConfirmedEvent", "PaymentRefundedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        if ("PaymentConfirmedEvent".equals(event.eventType())) {
            PaymentConfirmedEvent payload = event.payloadAs(PaymentConfirmedEvent.class);
            // 幂等锚 = billId(每笔款项单唯一);客户归属按 biz 三元组反查
            points.award(payload.orgId(), payload.bizAddon(), payload.billId(),
                    payload.bizType(), payload.bizId(), payload.amount());
            return;
        }
        PaymentRefundedEvent payload = event.payloadAs(PaymentRefundedEvent.class);
        points.refundDeduct(payload.orgId(), String.valueOf(payload.originBillId()),
                String.valueOf(payload.billId()));
    }
}
