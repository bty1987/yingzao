package com.yingzao.addons.api.event;

/**
 * 商品价格变更领域事件(契约,发布方 addon-basedata):标准售价/限价/倍率变化后发布。
 * 金额口径 = 厘(×1000 定点整数);消费方(addon-quotation/addon-sales)据此刷新价格缓存。
 */
public record GoodsPriceChangedEvent(Long goodsId, Long orgId, Long selling, Long confine, Integer unitRate) {
}
