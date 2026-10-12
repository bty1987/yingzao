package com.yingzao.addons.api.event;

/**
 * 兑换单领域事件(契约,发布方 addon-member):外部积分商城落单成功(积分与库存同事务扣减后)发布。
 * exchangeNo 为外部商城单号(全局唯一幂等锚);pointTotal = 本单消耗积分(整数积分,非厘)。
 * 经内核事件总线投递,订阅方必须幂等(eventKey = "member-exchange:{exchangeId}:created")。
 */
public record ExchangeCreatedEvent(Long exchangeId, Long orgId, String exchangeNo, Long customerId,
        Integer pointType, Long pointTotal, Long orderId, Long operatorId) {
}
