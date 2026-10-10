package com.yingzao.addons.api.event;

/**
 * 客源领域事件(契约,发布方 addon-customer):客源分配成功后发布,通知中心等订阅方据此触达责任人。
 * 事件只携带 ID 与不可变快照;mode 为分配策略标识(manual/auto)。
 */
public record LeadAssignedEvent(Long chanceId, Long leadId, Long orgId, Long assigneeId, String mode) {
}
