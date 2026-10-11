package com.yingzao.addon.sales;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.addons.api.event.PaymentConfirmedEvent;
import com.yingzao.addons.api.event.PaymentRefundedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 款项事件回程车:总线 SPI 消费 addon-payment 三事件,只处理 biz 三元组 =
 * (sales, order, 销售单id)。收款→款项轴 1 已付;确认→2 已确认+按品牌生成预结算行;
 * 退款完成→初始态退货单置已退款。消费失败总线重试直至死信,主流程早已提交。
 */
@Component
public class SalesPaymentConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SalesPaymentConsumer.class);

    private final SalesOrderService orders;

    public SalesPaymentConsumer(@Lazy SalesOrderService orders) {
        this.orders = orders;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCollectedEvent", "PaymentConfirmedEvent", "PaymentRefundedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "PaymentCollectedEvent" -> {
                PaymentCollectedEvent p = event.payloadAs(PaymentCollectedEvent.class);
                Long saleId = saleIdOf(p.bizAddon(), p.bizType(), p.bizId(), p.billId());
                if (saleId != null) {
                    orders.onPaymentCollected(p.orgId(), saleId);
                    log.info("sales order {} fullpay collected by bill {}", saleId, p.billId());
                }
            }
            case "PaymentConfirmedEvent" -> {
                PaymentConfirmedEvent p = event.payloadAs(PaymentConfirmedEvent.class);
                Long saleId = saleIdOf(p.bizAddon(), p.bizType(), p.bizId(), p.billId());
                if (saleId != null) {
                    orders.onPaymentConfirmed(p.orgId(), saleId,
                            p.confirmerId() == null ? 0 : p.confirmerId());
                    log.info("sales order {} fullpay confirmed by bill {}", saleId, p.billId());
                }
            }
            case "PaymentRefundedEvent" -> {
                PaymentRefundedEvent p = event.payloadAs(PaymentRefundedEvent.class);
                Long saleId = saleIdOf(p.bizAddon(), p.bizType(), p.bizId(), p.billId());
                if (saleId != null) {
                    orders.onPaymentRefunded(p.orgId(), saleId);
                    log.info("sales order {} refund done by bill {}", saleId, p.billId());
                }
            }
            default -> {
            }
        }
    }

    private Long saleIdOf(String bizAddon, String bizType, String bizId, long billId) {
        if (!SalesOrderService.BIZ_ADDON.equals(bizAddon)
                || !SalesOrderService.BIZ_TYPE_ORDER.equals(bizType)) {
            return null;
        }
        try {
            return Long.parseLong(bizId);
        } catch (NumberFormatException e) {
            log.error("sales payment event with unparsable bizId={}, billId={}", bizId, billId);
            return null;
        }
    }
}
