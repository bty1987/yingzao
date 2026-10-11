package com.yingzao.addons.api.event;

/**
 * 出入库单明细删除领域事件(契约,发布方 addon-inventory):初始态单据删行时发布,
 * 库存已同步回冲,订阅方(如 addon-sales 出货余量回加)据此回写自己的账。必须幂等,
 * 建议以 eventKey = "inventory:manifest:{manifestId}:itemRemoved" 做去重锚点。
 * 修老缺陷:INIT 态删行不回加销售侧出货余量,导致余量永久多扣——营造中回补由订阅方完成。
 */
public record StockManifestItemRemovedEvent(Long manifestId, Long orgId, Integer type, Long itemId,
                                            Long goodsId, Long colorId, Long formatId,
                                            Long warehouseInId, Long warehouseOutId, Long num,
                                            Long salesItemId, Long operatorId) {
}
