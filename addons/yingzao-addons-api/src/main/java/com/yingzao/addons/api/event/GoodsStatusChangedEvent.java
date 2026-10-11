package com.yingzao.addons.api.event;

/**
 * 商品启停领域事件(契约,发布方 addon-basedata):status 0=启用 1=停用。
 * 取代老系统 updateGoodsForStatus 直落 VO 值无校验无留痕——启停必须显式端点,事件即留痕。
 */
public record GoodsStatusChangedEvent(Long goodsId, Long orgId, Integer status) {
}
