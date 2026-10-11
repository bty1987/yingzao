package com.yingzao.addons.api.event;

/**
 * 报价领域事件(契约,发布方 addon-quotation):报价单创建后发布。
 * 携装修单 orderId 供下游(通知/分配/定额)按单聚合;经内核事件总线投递,
 * 订阅方必须幂等,建议以 eventKey = "quotation:{quotationId}:created" 做去重锚点。
 */
public record QuotationCreatedEvent(Long quotationId, Long orgId, Long orderId,
                                    String name, Long operatorId) {
}
