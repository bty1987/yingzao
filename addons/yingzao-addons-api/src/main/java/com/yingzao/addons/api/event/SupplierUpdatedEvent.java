package com.yingzao.addons.api.event;

/**
 * 厂商主数据领域事件(契约,发布方 addon-supplier):厂商档案编辑后发布。
 * 消费方据此失效本地缓存的厂商名称快照(快照自持,不回写历史单据)。
 */
public record SupplierUpdatedEvent(Long supplierId, Long orgId) {
}
