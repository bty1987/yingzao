package com.yingzao.addons.api.event;

/** 增减差量单已提交(quotation 域):携差量款项单 id 与正/负聚合(厘)。eventKey=ar-submitted-{arId}。 */
public record QuotationAddReduceSubmittedEvent(Long arId, Long orgId, Long orderId, Long dpId,
                                               Long totalAdd, Long totalSub, String memo) {
}
