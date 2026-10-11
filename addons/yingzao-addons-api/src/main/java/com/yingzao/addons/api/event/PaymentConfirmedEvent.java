package com.yingzao.addons.api.event;

/**
 * 款项确认领域事件(契约,发布方 addon-payment):财务确认款项(1 已收 → 2 已确认终态)后发布。
 * 金额口径 = 厘;经内核事件总线投递,订阅方必须幂等(eventKey = "bill:{billId}:confirmed")。
 * 厂商结算推进/单据完结联动等归业务插件处理。
 */
public record PaymentConfirmedEvent(Long billId, Long orgId, String bizAddon, String bizType, String bizId,
        String typeCode, Long amount, Long confirmerId) {
}
