package com.yingzao.addons.api.event;

/**
 * 礼品库存变动领域事件(契约,发布方 addon-member):出入库/盘点/兑换扣减/撤销回补后发布。
 * action 为业务动作协议键(in/out/checkIn/checkOut/exchange/revoke);
 * change 带符号(正增负扣),afterNum 为变动后库存快照;num 为绝对值。
 * 经内核事件总线投递,订阅方必须幂等(eventKey = "member-gift-stock:{recordId}")。
 */
public record GiftStockChangedEvent(Long giftId, Long orgId, String giftNo, String action, Long num,
        Long change, Long afterNum, String bizAddon, String bizType, String bizId) {
}
