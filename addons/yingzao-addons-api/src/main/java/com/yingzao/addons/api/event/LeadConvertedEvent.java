package com.yingzao.addons.api.event;

/**
 * 客源领域事件(契约,发布方 addon-customer):客源转化后发布,客户档案 addon 订阅建档。
 * 事件只携带 ID 与不可变快照。
 */
public record LeadConvertedEvent(Long leadId, Long orgId, String name, String phone) {
}
