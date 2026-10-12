package com.yingzao.addon.member;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.DecorationSignedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 装修签约 → 自动核销消费器(总线 SPI,修老 DecorationPaymentServiceImpl 签约自动核销链)。
 * 组织开关(member_config.auto_sign_consume)关闭或缺省时静默跳过;核销说明取组织配置,
 * 废老 '签约自动核销' 硬编码字面量。幂等:核销为条件 UPDATE 状态守卫,事件重放天然幂等;
 * 守卫失配由 Service 内部 BizException 消化(log 留痕跳过),无基础设施异常上抛场景。
 */
@Component
public class SignAutoConsumeConsumer implements YzEventConsumer {

    private final MemberExchangeService exchanges;

    public SignAutoConsumeConsumer(@Lazy MemberExchangeService exchanges) {
        this.exchanges = exchanges;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DecorationSignedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        DecorationSignedEvent payload = event.payloadAs(DecorationSignedEvent.class);
        exchanges.autoSignConsume(payload.orgId(), payload.orderId());
    }
}
