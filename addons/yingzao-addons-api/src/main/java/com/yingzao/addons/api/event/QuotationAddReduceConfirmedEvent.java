package com.yingzao.addons.api.event;

/** 增减差量单已确认(终态;净额 netTotal=totalAdd-totalSub,厘)。eventKey=ar-confirmed-{arId}。 */
public record QuotationAddReduceConfirmedEvent(Long arId, Long orgId, Long orderId, Long netTotal) {
}
