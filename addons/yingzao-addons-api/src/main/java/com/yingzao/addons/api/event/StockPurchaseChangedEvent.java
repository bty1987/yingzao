package com.yingzao.addons.api.event;

import java.util.List;

/**
 * 采购单变更领域事件(契约,发布方 addon-inventory):采购单取消或删项时发布,
 * 释放的采购占用余量由订阅方(如 addon-sales 待采购余量)回写自己的账。必须幂等,
 * 建议以 eventKey = "inventory:purchase:{purchaseId}:{action}" 做去重锚点。
 * 修老缺陷:punum 占用后永不释放(取消/删除采购永久烧掉销售项可采购余量)——
 * 营造中释放走事件,销售域订阅回补,inventory 零直写 sales 表。
 */
public record StockPurchaseChangedEvent(Long purchaseId, Long orgId, String action,
                                        List<Item> items, Long operatorId) {

    /** salesItemId 为空 = 无销售挂接的采购行(释放无对象,仅记录);num = 本次释放数量(千分位)。 */
    public record Item(Long itemId, Long salesItemId, Long goodsId, Long num, Long inprice) {
    }

    /** action 协议键:cancelled = 整单取消(释放全部余量);itemRemoved = 删项(释放该项余量)。 */
    public static final String ACTION_CANCELLED = "cancelled";
    public static final String ACTION_ITEM_REMOVED = "itemRemoved";
}
