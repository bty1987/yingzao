package com.yingzao.addons.api.event;

/**
 * 款项收款领域事件(契约,发布方 addon-payment):款项单收款成功(0 未收 → 1 已收)后发布。
 * 金额口径 = 厘(×1000 定点整数);经内核事件总线投递(消费确认+失败重试+死信),
 * 订阅方必须幂等(同一事件可能被重放),建议以 eventKey = "bill:{billId}:collected" 做去重锚点。
 * 业务回写(装修单升定金/增减项置已付/销售单联动等)全部归各业务插件自己处理,payment 零业务知识。
 */
public record PaymentCollectedEvent(Long billId, Long orgId, String bizAddon, String bizType, String bizId,
        String typeCode, Long amount, Long receiptId, Long collectorId) {
}
