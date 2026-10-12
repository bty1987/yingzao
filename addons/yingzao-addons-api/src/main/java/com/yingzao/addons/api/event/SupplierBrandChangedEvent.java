package com.yingzao.addons.api.event;

/**
 * 厂商品牌关联领域事件(契约,发布方 addon-supplier)。
 * action 协议键:bound 关联 / unbound 解绑 / primary 设直属。
 * 解绑不删历史(结算/选材快照自持);直属位每品牌唯一,切换语义(新直属顶旧直属)。
 */
public record SupplierBrandChangedEvent(Long supplierId, Long orgId, Long brandId, String action) {
}
