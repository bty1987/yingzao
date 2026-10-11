package com.yingzao.core.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件投递调度器:提交后同步投递一轮之后的兜底——失败退避到期的、应用重启遗留的、
 * 投递器崩溃超时回收的,都在这里被再次捡起。默认 10 秒一轮,单轮上限 50 条。
 */
@Component
public class YzEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(YzEventDispatcher.class);

    private final YzEventBus bus;

    public YzEventDispatcher(YzEventBus bus) {
        this.bus = bus;
    }

    @Scheduled(fixedDelayString = "${yingzao.events.poll-interval-millis:10000}",
            initialDelayString = "${yingzao.events.poll-initial-delay-millis:15000}")
    public void poll() {
        try {
            bus.dispatchDue(50);
        } catch (Exception e) {
            log.warn("event dispatch round failed: {}", e.getMessage());
        }
    }
}
