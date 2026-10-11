package com.yingzao.addons.api.event;

/**
 * 装修单领域事件(契约,发布方 addon-decoration):签约成功(合同线 3)后发布。
 * contractAmount 为签约合同总额(厘);经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "order:{orderId}:signed" 做去重锚点。
 */
public record DecorationSignedEvent(Long orderId, Long orgId, String orderNo, Long contractAmount,
        Long operatorId) {
}
