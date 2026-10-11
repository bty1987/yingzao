package com.yingzao.addons.api.event;

/**
 * 放线失败领域事件(契约,发布方 addon-construction):放线单置失败(3)后发布,
 * 供订阅方通知设计师(老系统 failureDrawLine 站内信语义,事件化解跨域直调)。
 * 经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "construction:drawline:{drawlineId}:failed" 做去重锚点。
 */
public record ConstructionDrawlineFailedEvent(Long drawlineId, Long orgId, Long orderId,
        String remark, Long operatorId) {
}
