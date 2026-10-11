package com.yingzao.addons.api.event;

/**
 * 款项退款领域事件(契约,发布方 addon-payment):退款审批通过并执行完毕后发布。
 * billId = 退款款项单 id;originBillId = 原款项单 id(执行后置 3 已退款终态,原状态快照在退款单上——
 * 修老系统「驳回顶态」缺陷:驳回/撤回不改原款项任何字段)。金额口径 = 厘;经内核事件总线投递,
 * 订阅方必须幂等(eventKey = "refund:{billId}:refunded")。
 * 作废联动(主材单/销售单随定金退款作废等)归业务插件处理。
 */
public record PaymentRefundedEvent(Long billId, Long originBillId, Long orgId, String bizAddon, String bizType,
        String bizId, String typeCode, Long amount, Long refundApprovalId, Long operatorId) {
}
