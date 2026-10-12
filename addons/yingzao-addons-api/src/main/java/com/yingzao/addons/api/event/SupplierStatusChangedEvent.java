package com.yingzao.addons.api.event;

/**
 * 厂商启停领域事件(契约,发布方 addon-supplier):启用⇄停用后发布。
 * status:0 启用 / 1 停用。停用只挡新挂单(业务插件经 SupplierApi.checkActive 校验),
 * 存量单据与历史快照不受影响(老系统借员工离职语义当厂商停用,无专属状态机,不迁)。
 */
public record SupplierStatusChangedEvent(Long supplierId, Long orgId, int status) {
}
