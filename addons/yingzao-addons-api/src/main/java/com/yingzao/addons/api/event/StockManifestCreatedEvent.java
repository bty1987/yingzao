package com.yingzao.addons.api.event;

import java.util.List;

/**
 * 出入库单创建领域事件(契约,发布方 addon-inventory):任何库存增减都伴随一张出入库单,
 * 建单事务内即时过账(「单据即库存」语义保留),本事件即库存流水的对外通知面。
 * 订阅方(如 addon-sales 按销售明细回写出货余量、addon-supplier 同步)必须幂等,
 * 建议以 eventKey = "inventory:manifest:{manifestId}:created" 做去重锚点。
 * 跨域红线:发布方零直写他域表,计数回写(老 xgnum 直写等)一律由订阅方自行完成。
 */
public record StockManifestCreatedEvent(Long manifestId, Long orgId, Integer type, String code,
                                        Long orderId, List<Item> items, Long operatorId) {

    /**
     * 单据明细行快照:type 见 addon-inventory 字典(1进货/2进退/3出货/4出返/5调拨/6采购入库/
     * 7领料出库/8领料退回/11盘盈/12盘亏);数量千分位(×1000),价格厘;
     * sourceItemId = 冲销/回链源明细(CR/XB→原货单明细,AR→原出库明细);salesItemId/requisitionItemId 为跨域锚点。
     */
    public record Item(Long itemId, Long goodsId, Long colorId, Long formatId,
                       Long warehouseInId, Long warehouseOutId, Long num, Long price,
                       Long salesItemId, Long requisitionItemId, Long sourceItemId) {
    }
}
