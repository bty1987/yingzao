package com.yingzao.addon.market;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.CardNoticeCreatedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;
import com.yingzao.core.notification.NotificationService;

/**
 * 留资通知消费器(总线 SPI):CardNoticeCreatedEvent → 内核通知中心站内信给名片主人
 * (修老「事务内同步 httpGet 明文 PII 外发」——事件只携 id,姓名/电话零出域零外发;
 * 通知可重复无害,重放=多一条,与 ConstructionNotifyConsumer 同口径)。
 * 仅注入 JdbcTemplate/通知中心/文案源,无 CardService 依赖——构造环天然不存在,@Lazy 免。
 */
@Component
public class CardNoticeConsumer implements YzEventConsumer {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final MessageSource messages;

    public CardNoticeConsumer(JdbcTemplate jdbc, NotificationService notifications,
            MessageSource messages) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("CardNoticeCreatedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        CardNoticeCreatedEvent payload = event.payloadAs(CardNoticeCreatedEvent.class);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.user_id, c.user_name, n.name from market_card c
                join market_card_notice n on n.card_id = c.id
                where c.id = ? and n.id = ?""", payload.cardId(), payload.noticeId());
        if (rows.isEmpty()) {
            return; // 留资随名片删除级联清(通知晚到无主,静默跳过语义)
        }
        Map<String, Object> row = rows.get(0);
        String visitor = (String) row.get("name");
        String owner = (String) row.get("user_name");
        String title = messages.getMessage("market.card.notify.title",
                new Object[] { visitor }, LocaleContextHolder.getLocale());
        String content = messages.getMessage("market.card.notify.content",
                new Object[] { visitor, owner }, LocaleContextHolder.getLocale());
        notifications.send(new NotificationService.SendCmd(payload.orgId(),
                ((Number) row.get("user_id")).longValue(), title, content, "market",
                "card.notice", String.valueOf(payload.noticeId())));
    }
}
