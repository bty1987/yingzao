package com.yingzao.addon.decoration;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.DecorationCreatedEvent;
import com.yingzao.addons.api.event.DecorationSignedEvent;
import com.yingzao.addons.api.event.DecorationStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 装修单事件通知消费器:走内核事件总线 SPI 消费本插件发布的三个领域事件,
 * 给设计师+销售落站内通知(PRD 验收:关键节点经领域事件触发通知中心触达责任人)。
 * 幂等口径:通知可重复无害(重放=多一条),与 addon-example 示范消费器同口径;
 * 严谨去重以 eventKey 建表 V2。
 */
@Component
public class DecorationNotifyConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecorationNotifyConsumer.class);

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final NotificationService notifications;

    public DecorationNotifyConsumer(JdbcTemplate jdbc, MessageSource messages, NotificationService notifications) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.notifications = notifications;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DecorationCreatedEvent", "DecorationSignedEvent", "DecorationStatusChangedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        var locale = LocaleContextHolder.getLocale();
        switch (event.eventType()) {
            case "DecorationCreatedEvent" -> {
                DecorationCreatedEvent payload = event.payloadAs(DecorationCreatedEvent.class);
                Map<String, Object> order = jdbc.queryForMap(
                        "select designer_id, salesman_id, customer_name, house_label from decoration_order where id = ?",
                        payload.orderId());
                notify(payload.orgId(),
                        new long[] { orZero(payload.designerId()), orZero(payload.salesmanId()),
                                orZero(((Number) order.get("designer_id")).longValue()),
                                orZero(((Number) order.get("salesman_id")).longValue()) },
                        "decoration.notify.created.title", new Object[] { payload.orderNo() },
                        "decoration.notify.created.content", new Object[] { payload.orderNo(),
                                typeLabel(payload.type(), locale), order.get("customer_name"),
                                order.get("house_label") },
                        event.eventKey());
            }
            case "DecorationSignedEvent" -> {
                DecorationSignedEvent payload = event.payloadAs(DecorationSignedEvent.class);
                notifyOrder(payload.orgId(), payload.orderId(),
                        "decoration.notify.signed.title", new Object[] { payload.orderNo() },
                        "decoration.notify.signed.content", new Object[] { payload.orderNo(),
                                java.math.BigDecimal.valueOf(payload.contractAmount(), 3).toPlainString() },
                        event.eventKey());
            }
            case "DecorationStatusChangedEvent" -> {
                DecorationStatusChangedEvent payload = event.payloadAs(DecorationStatusChangedEvent.class);
                notifyOrder(payload.orgId(), payload.orderId(),
                        "decoration.notify.status.title", new Object[] { payload.orderNo() },
                        "decoration.notify.status.content", new Object[] { payload.orderNo(),
                                statusLabel(payload.toStatus(), locale) },
                        event.eventKey());
            }
            default -> {
                // 未知类型:总线按 eventTypes() 匹配投递,理论不可达;留痕即可
                log.warn("unexpected event type {} reached DecorationNotifyConsumer", event.eventType());
            }
        }
    }

    private void notifyOrder(long orgId, long orderId, String titleKey, Object[] titleArgs,
            String contentKey, Object[] contentArgs, String eventKey) {
        Map<String, Object> order = jdbc.queryForMap(
                "select designer_id, salesman_id from decoration_order where id = ?", orderId);
        notify(orgId, new long[] { orZero(((Number) order.get("designer_id")).longValue()),
                orZero(((Number) order.get("salesman_id")).longValue()) },
                titleKey, titleArgs, contentKey, contentArgs, eventKey);
    }

    private void notify(long orgId, long[] userIds, String titleKey, Object[] titleArgs,
            String contentKey, Object[] contentArgs, String eventKey) {
        var locale = LocaleContextHolder.getLocale();
        String title = messages.getMessage(titleKey, titleArgs, locale);
        String content = messages.getMessage(contentKey, contentArgs, locale);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long userId : userIds) {
            if (userId <= 0 || !seen.add(userId)) {
                continue;
            }
            notifications.send(new NotificationService.SendCmd(orgId, userId, title, content,
                    DecorationService.BIZ_ADDON, "notify", eventKey));
        }
    }

    private String statusLabel(Integer status, java.util.Locale locale) {
        if (status == null) {
            return "";
        }
        return messages.getMessage("decoration.status." + status, null, locale);
    }

    private String typeLabel(Integer type, java.util.Locale locale) {
        if (type == null) {
            return "";
        }
        return messages.getMessage("decoration.type." + type, null, locale);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
