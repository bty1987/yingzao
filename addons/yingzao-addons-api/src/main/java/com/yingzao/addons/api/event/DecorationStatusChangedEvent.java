package com.yingzao.addons.api.event;

/**
 * 装修单领域事件(契约,发布方 addon-decoration):合同线状态变更后发布(通用,含终态)。
 * action 为业务动作协议键(create/deposit/presign/sign/resign/obsolete/terminate/account/finish),
 * fromStatus/toStatus 为合同线迁移前后值;确认线/工程线/设计线的推进不在本事件
 * (各自属设计交付与施工域口径)。经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "order:{orderId}:{action}" 做去重锚点。
 */
public record DecorationStatusChangedEvent(Long orderId, Long orgId, String orderNo,
        Integer fromStatus, Integer toStatus, String action, Long operatorId) {
}
