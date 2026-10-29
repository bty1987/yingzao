package com.yingzao.addon.sales;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 外销收款回程车(总线 SPI):只认 biz 三元组 (sales, oales, 外销单id) 且
 * typeCode=sales_oales 的收款事件 → 2 已确认 → 3 已收款(条件 UPDATE 幂等)。
 * 老「复核档」消亡的语义等价物(修 collectOalesPayment ptids 断链可重复核销);
 * 退款走 payment 通用链不回退状态(净额由款项聚合体现)。
 * @Lazy 断环:OalesService 构造注入 YzEventBus(结算事件发布),总线又收集本消费器
 * (第 89 次 AcceptanceVisitConsumer 同款,启动即死家族)。
 */
@Component
public class OalesPaymentConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OalesPaymentConsumer.class);

    private final OalesService oales;

    public OalesPaymentConsumer(@Lazy OalesService oales) {
        this.oales = oales;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCollectedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        PaymentCollectedEvent p = event.payloadAs(PaymentCollectedEvent.class);
        if (!OalesService.BIZ_ADDON.equals(p.bizAddon()) || !OalesService.BIZ_TYPE.equals(p.bizType())
                || !OalesService.TYPE_CODE.equals(p.typeCode())) {
            return;
        }
        try {
            oales.onPaymentCollected(p.orgId(), Long.parseLong(p.bizId()));
            log.info("oales {} collected by bill {}", p.bizId(), p.billId());
        } catch (NumberFormatException e) {
            log.error("oales payment event with unparsable bizId={}, billId={}", p.bizId(), p.billId());
        }
    }
}
