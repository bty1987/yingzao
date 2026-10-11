package com.yingzao.addon.customer;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.InboundAcceptedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 营销进线承接消费者:走内核事件总线 SPI 消费 addon-market 的 InboundAcceptedEvent,
 * 单事务走「认领→解密留痕→建客源(/派发机会)→回写」链;业务失败整体回滚后置失败态留原因
 * (进线滞留失败态可见可重试,修老「登记永远成功」式静默与孤儿事务)。
 * @Lazy 断环:总线构造器收集全部消费者,而服务又依赖总线——消费者侧延迟拿到服务。
 */
@Component
public class MarketInboundConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketInboundConsumer.class);

    private final InboundAcceptService accepts;

    public MarketInboundConsumer(@Lazy InboundAcceptService accepts) {
        this.accepts = accepts;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("InboundAcceptedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        InboundAcceptedEvent inbound = event.payloadAs(InboundAcceptedEvent.class);
        try {
            accepts.accept(inbound.inboundId(), inbound.orgId(), inbound.name(), inbound.phoneHmac(),
                    inbound.acceptMode(), inbound.note());
            log.info("market inbound {} accepted (org {})", inbound.inboundId(), inbound.orgId());
        } catch (Exception e) {
            log.warn("market inbound {} accept failed: {}", inbound.inboundId(), e.getMessage());
            accepts.markFailed(inbound.inboundId(), inbound.orgId(), e.getMessage());
        }
    }
}
