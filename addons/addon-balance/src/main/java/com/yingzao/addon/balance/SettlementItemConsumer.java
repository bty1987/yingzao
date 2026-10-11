package com.yingzao.addon.balance;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.SettlementItemReadyEvent;
import com.yingzao.addons.api.event.SettlementItemWithdrawnEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 明细可结算事件回程车(总线 SPI):上游插件发布「明细可结算/明细退结算」,本域聚桶落快照行/
 * 摘行重算。快照落行+uk 幂等(同键同金额重放静默跳过);锁定后退回消费失败转死信可见。
 * 消费失败总线重试直至死信,上游主流程早已提交(at-least-once)。
 */
@Component
public class SettlementItemConsumer implements YzEventConsumer {

    private final BalanceSettlementService settlements;

    public SettlementItemConsumer(@Lazy BalanceSettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("SettlementItemReadyEvent", "SettlementItemWithdrawnEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "SettlementItemReadyEvent" -> settlements.onItemReady(
                    event.payloadAs(SettlementItemReadyEvent.class));
            case "SettlementItemWithdrawnEvent" -> settlements.onItemWithdrawn(
                    event.payloadAs(SettlementItemWithdrawnEvent.class));
            default -> {
            }
        }
    }
}
