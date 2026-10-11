package com.yingzao.addon.decoration;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.ConstructionAcceptedEvent;
import com.yingzao.addons.api.event.ConstructionStartedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 施工事件回程车:走内核事件总线 SPI 消费 addon-construction 的开工/验收事件,
 * 回写装修单工程线(四线独立,修老跨模块直写)——
 * 开工点火(放线成功/补录)→ 工程线 0→1;验收竣工 → 工程线 1→2。
 * 条件 UPDATE,事件重放天然幂等;工程线已越过目标态时静默放行(量房先行开工等合法前置)。
 * @Lazy 断环:总线构造器收集全部消费者,而服务又依赖总线——消费者侧延迟拿到服务。
 */
@Component
public class DecorationConstructionConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecorationConstructionConsumer.class);

    private final DecorationService orders;

    public DecorationConstructionConsumer(@Lazy DecorationService orders) {
        this.orders = orders;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ConstructionStartedEvent", "ConstructionAcceptedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "ConstructionStartedEvent" -> {
                ConstructionStartedEvent payload = event.payloadAs(ConstructionStartedEvent.class);
                orders.onConstructionStarted(payload.orgId(), payload.orderId(),
                        payload.operatorId() == null ? 0 : payload.operatorId());
                log.info("decoration order {} project started by construction {}",
                        payload.orderId(), payload.constructionId());
            }
            case "ConstructionAcceptedEvent" -> {
                ConstructionAcceptedEvent payload = event.payloadAs(ConstructionAcceptedEvent.class);
                orders.onConstructionAccepted(payload.orgId(), payload.orderId(),
                        payload.operatorId() == null ? 0 : payload.operatorId());
                log.info("decoration order {} project finished by construction acceptance {}",
                        payload.orderId(), payload.acceptanceId());
            }
            default -> {
            }
        }
    }
}
