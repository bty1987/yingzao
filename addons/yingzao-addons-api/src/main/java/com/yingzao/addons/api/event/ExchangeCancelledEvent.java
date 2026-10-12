package com.yingzao.addons.api.event;

/**
 * 兑换单撤销领域事件(契约,发布方 addon-member):兑换单整单撤销(积分与库存对称回补后)发布。
 * 撤销必须校验状态(受理态才可撤,修老系统 cancelMemberOrder 不校验状态可作废已核销的缺陷)。
 * 经内核事件总线投递,订阅方必须幂等(eventKey = "member-exchange:{exchangeId}:cancelled")。
 */
public record ExchangeCancelledEvent(Long exchangeId, Long orgId, String exchangeNo, Long customerId,
        Long pointTotal, Long operatorId) {
}
