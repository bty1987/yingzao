package com.yingzao.addon.example;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 示范消费器:走内核事件总线 SPI(YzEventConsumer)消费 addon-payment 的收款事件,
 * 给收款操作人落一条站内通知作为「消费留痕」——验证总线端到端(出站箱 → 消费确认)的参考实现。
 * 与 Spring @EventListener 的差异:失败会被总线重试直至死信,因此消费必须幂等——
 * 这里以通知表天然可重复的性质兜底(重放=多一条通知,无害);严谨场景应以 eventKey 建去重表。
 */
@Component
public class PaymentEventDemoConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventDemoConsumer.class);

    private final NotificationService notifications;
    private final MessageSource messages;

    public PaymentEventDemoConsumer(NotificationService notifications, MessageSource messages) {
        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCollectedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        PaymentCollectedEvent payment = event.payloadAs(PaymentCollectedEvent.class);
        if (payment.collectorId() == null) {
            return;
        }
        var locale = LocaleContextHolder.getLocale();
        String title = messages.getMessage("example.payment.consumed.title", null, locale);
        String content = messages.getMessage("example.payment.consumed.content",
                new Object[] { payment.bizAddon() + "/" + payment.bizType() + "/" + payment.bizId(),
                        java.math.BigDecimal.valueOf(payment.amount(), 3).toPlainString() },
                locale);
        notifications.send(new NotificationService.SendCmd(payment.orgId(), payment.collectorId(),
                title, content, "example", "payment.collected.consumed", String.valueOf(event.taskId())));
        log.info("demo consumer processed PaymentCollectedEvent, taskId={} billId={}",
                event.taskId(), payment.billId());
    }
}
