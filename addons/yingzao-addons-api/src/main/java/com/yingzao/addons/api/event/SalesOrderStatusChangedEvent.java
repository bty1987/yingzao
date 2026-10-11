package com.yingzao.addons.api.event;

/**
 * 销售单主单轴状态变更领域事件(契约,发布方 addon-sales):全退/倒单/完结/废单等
 * 主单轴跃迁后发布(款项轴/结算轴走各自消费链不在此列),action 协议键见常量。
 * 订阅方必须幂等,建议以 eventKey = "sales:{saleId}:{action}" 做去重锚点。
 */
public record SalesOrderStatusChangedEvent(Long saleId, Long orgId, Long orderId, String action,
                                           Integer fromStatus, Integer toStatus, Long operatorId) {

    /** action 协议键:全退(退货审批批准)/ 倒单(退货审批批准)/ 完结 / 废单。 */
    public static final String ACTION_FULL_REFUND = "fullRefund";
    public static final String ACTION_COUNTERMAND = "countermand";
    public static final String ACTION_FINISH = "finish";
    public static final String ACTION_TERMINATE = "terminate";
}
