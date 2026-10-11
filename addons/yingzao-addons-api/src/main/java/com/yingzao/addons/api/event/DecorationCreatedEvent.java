package com.yingzao.addons.api.event;

/**
 * 装修单领域事件(契约,发布方 addon-decoration):装修单登记后发布。
 * 事件只携带 ID 与不可变快照(customer/house 仅 ID,展示名以各域档案为准);
 * 经内核事件总线投递(消费确认+失败重试+死信),订阅方必须幂等,
 * 建议以 eventKey = "order:{orderId}:created" 做去重锚点。
 */
public record DecorationCreatedEvent(Long orderId, Long orgId, String orderNo, Long customerId,
        Long houseId, Integer type, Long designerId, Long salesmanId) {
}
