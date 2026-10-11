package com.yingzao.addon.decoration;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 款项收款事件回程车(事件回程首次实战):走内核事件总线 SPI 消费 addon-payment 的
 * PaymentCollectedEvent,只处理 biz 三元组 = (decoration, order, 装修单id)——
 * 定金款线首收 → 合同线 0 初始 → 1 定金(条件 UPDATE,事件重放天然幂等);
 * 非定金类型(签约款线等)不回写状态,仅留痕。
 * 消费失败会被总线重试直至死信,主流程(收款事务)早已提交,这里只做旁路回写。
 * @Lazy 断环:总线构造器收集全部消费者,而服务又依赖总线——消费者侧延迟拿到服务。
 */
@Component
public class DecorationPaymentConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecorationPaymentConsumer.class);

    private final DecorationService orders;

    public DecorationPaymentConsumer(@Lazy DecorationService orders) {
        this.orders = orders;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCollectedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        PaymentCollectedEvent payment = event.payloadAs(PaymentCollectedEvent.class);
        if (!DecorationService.BIZ_ADDON.equals(payment.bizAddon())
                || !DecorationService.BIZ_TYPE.equals(payment.bizType())) {
            return;
        }
        long orderId;
        try {
            orderId = Long.parseLong(payment.bizId());
        } catch (NumberFormatException e) {
            log.error("payment collected for decoration with unparsable bizId={}, billId={}",
                    payment.bizId(), payment.billId());
            return;
        }
        orders.onPaymentCollected(payment.orgId(), orderId, payment.typeCode(),
                payment.collectorId() == null ? 0 : payment.collectorId());
        log.info("decoration order {} deposit elevated by bill {} (typeCode={})",
                orderId, payment.billId(), payment.typeCode());
    }
}
