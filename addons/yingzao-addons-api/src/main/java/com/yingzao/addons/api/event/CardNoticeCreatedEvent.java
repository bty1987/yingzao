package com.yingzao.addons.api.event;

/**
 * 名片留资创建事件(契约,发布方 addon-market 名片子域):访客提交留资落库后与业务同事务发布,
 * addon-market 内 CardNoticeConsumer 消费经内核通知中心站内信通知名片主人(修老「事务内同步
 * httpGet 明文 PII 拼 GET query 外发」三重违例——事件只携带 id,姓名电话零出事务零出域)。
 * 建议以 eventKey = "card-notice:{noticeId}" 做去重锚点。
 */
public record CardNoticeCreatedEvent(Long orgId, Long cardId, Long noticeId) {
}
