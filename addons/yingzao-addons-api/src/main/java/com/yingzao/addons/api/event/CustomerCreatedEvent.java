package com.yingzao.addons.api.event;

/**
 * 客户档案领域事件(契约,发布方 addon-customerprofile):客户主档案建档后发布。
 * 事件只携带 ID 与不可变快照;phoneHmac 为手机号 HMAC(等值关联键),不携带明文 PII。
 */
public record CustomerCreatedEvent(Long customerId, Long orgId, String name, String phoneHmac) {
}
