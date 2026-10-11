package com.yingzao.addon.construction;

import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.ConstructionAcceptedEvent;
import com.yingzao.addons.api.event.ConstructionDrawlineFailedEvent;
import com.yingzao.addons.api.event.ConstructionScheduleStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 施工事件通知消费器:走内核事件总线 SPI 消费本插件领域事件,触达责任人(PRD 验收:
 * 节点开完工/验收发布领域事件,通知中心触达责任人)——监理收节点开完工通知,
 * 监理+设计师收验收竣工通知,设计师收放线失败通知(老 failureDrawLine 站内信语义事件化)。
 * 幂等口径:通知可重复无害(重放=多一条),与 DecorationNotifyConsumer 同口径。
 */
@Component
public class ConstructionNotifyConsumer implements YzEventConsumer {

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final NotificationService notifications;
    private final com.yingzao.addons.api.DecorationApi decorations;

    public ConstructionNotifyConsumer(JdbcTemplate jdbc, MessageSource messages,
            NotificationService notifications, com.yingzao.addons.api.DecorationApi decorations) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.notifications = notifications;
        this.decorations = decorations;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ConstructionScheduleStatusChangedEvent", "ConstructionAcceptedEvent",
                "ConstructionDrawlineFailedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "ConstructionScheduleStatusChangedEvent" -> {
                ConstructionScheduleStatusChangedEvent payload =
                        event.payloadAs(ConstructionScheduleStatusChangedEvent.class);
                Long supervisor = supervisorOf(payload.orderId());
                if (supervisor == null) {
                    return;
                }
                send(payload.orgId(), new long[] { supervisor },
                        "construction.notify.schedule.title",
                        new Object[] { payload.nodeName(), actionLabel(payload.action()) },
                        "construction.notify.schedule.content",
                        new Object[] { payload.nodeName(), actionLabel(payload.action()) },
                        event.eventKey());
            }
            case "ConstructionAcceptedEvent" -> {
                ConstructionAcceptedEvent payload = event.payloadAs(ConstructionAcceptedEvent.class);
                Long supervisor = supervisorOf(payload.orderId());
                long designer = designerOf(payload.orgId(), payload.orderId());
                send(payload.orgId(), new long[] { supervisor == null ? 0 : supervisor, designer },
                        "construction.notify.accepted.title", new Object[] {},
                        "construction.notify.accepted.content", new Object[] {},
                        event.eventKey());
            }
            case "ConstructionDrawlineFailedEvent" -> {
                ConstructionDrawlineFailedEvent payload =
                        event.payloadAs(ConstructionDrawlineFailedEvent.class);
                long designer = designerOf(payload.orgId(), payload.orderId());
                send(payload.orgId(), new long[] { designer },
                        "construction.notify.drawlineFailed.title", new Object[] {},
                        "construction.notify.drawlineFailed.content",
                        new Object[] { payload.remark() == null ? "" : payload.remark() },
                        event.eventKey());
            }
            default -> {
            }
        }
    }

    private Long supervisorOf(long orderId) {
        var rows = jdbc.queryForList(
                "select supervisor_id from construction_order where order_id = ?", orderId);
        if (rows.isEmpty() || rows.get(0).get("supervisor_id") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("supervisor_id")).longValue();
    }

    private long designerOf(long orgId, long orderId) {
        // 优先施工单快照;放线失败等场景可能尚未点火,经 DecorationApi 契约兜底(禁直查他域表)
        var rows = jdbc.queryForList(
                "select designer_id from construction_order where order_id = ?", orderId);
        if (!rows.isEmpty() && rows.get(0).get("designer_id") != null) {
            return ((Number) rows.get(0).get("designer_id")).longValue();
        }
        return decorations.queryOrder(orgId, orderId)
                .map(com.yingzao.addons.api.DecorationApi.OrderSummary::designerId).orElse(0L);
    }

    private String actionLabel(String action) {
        try {
            return messages.getMessage("construction.action." + action, null,
                    LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return action == null ? "" : action;
        }
    }

    private void send(long orgId, long[] userIds, String titleKey, Object[] titleArgs,
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
                    ConstructionService.BIZ_ADDON, "notify", eventKey));
        }
    }
}
