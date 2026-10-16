package com.yingzao.addon.quotation;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentConfirmedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 增减差量付款回程消费器(总线 SPI):PaymentConfirmedEvent(bizAddon=quotation,
 * bizType=ar_diff)→ 差量单 1 已提交 → 2 已付款。状态条件 UPDATE 幂等(重放零变化)。
 * @Lazy 断环:服务构造器注入 YzEventBus(发布回程事件),总线又收集本消费器。
 */
@Component
public class AddReducePaymentConsumer implements YzEventConsumer {

    private final QuotationAddReduceService addReduce;

    public AddReducePaymentConsumer(@Lazy QuotationAddReduceService addReduce) {
        this.addReduce = addReduce;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentConfirmedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        PaymentConfirmedEvent payload = event.payloadAs(PaymentConfirmedEvent.class);
        if ("quotation".equals(payload.bizAddon()) && "ar_diff".equals(payload.bizType())) {
            addReduce.onPaymentConfirmed(payload.orgId(), payload.bizAddon(),
                    payload.bizType(), payload.bizId());
        }
    }
}
