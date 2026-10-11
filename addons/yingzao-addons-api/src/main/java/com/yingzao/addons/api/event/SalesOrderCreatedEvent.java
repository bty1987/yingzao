package com.yingzao.addons.api.event;

/**
 * 销售单创建领域事件(契约,发布方 addon-sales):门店为装修单开主材销售单落账后发布,
 * 订阅方(装修单详情销售单标签/通知等)必须幂等,
 * 建议以 eventKey = "sales:{saleId}:created" 做去重锚点。
 */
public record SalesOrderCreatedEvent(Long saleId, Long orgId, Long orderId, String orderNo,
                                     String saleCode, Long total, Long operatorId) {
}
