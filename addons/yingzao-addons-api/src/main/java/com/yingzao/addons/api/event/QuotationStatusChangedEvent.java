package com.yingzao.addons.api.event;

/**
 * 报价领域事件(契约,发布方 addon-quotation):定稿线/审核线状态变更后发布(通用)。
 * action 为业务动作协议键(lock/unlock/submit/audit/finish/setDefault),
 * from/to 为定稿线(status)与审核线(attstatus)迁移前后值(未变化的线为 null)。
 * 经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "quotation:{quotationId}:{action}" 做去重锚点。
 */
public record QuotationStatusChangedEvent(Long quotationId, Long orgId, Long orderId,
        Integer fromStatus, Integer toStatus, Integer fromAttstatus, Integer toAttstatus,
        String action, Long operatorId) {
}
