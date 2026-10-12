package com.yingzao.addons.api.event;

/**
 * 厂商主数据领域事件(契约,发布方 addon-supplier):厂商建档后发布。
 * 事件只携带 ID 与不可变快照;消费方(如结算归口/外辅通知)按 eventKey 幂等锚点去重。
 */
public record SupplierCreatedEvent(Long supplierId, Long orgId, String no, String name) {
}
