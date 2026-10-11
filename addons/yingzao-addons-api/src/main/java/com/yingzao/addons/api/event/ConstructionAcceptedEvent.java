package com.yingzao.addons.api.event;

/**
 * 施工验收领域事件(契约,发布方 addon-construction):验收提交且全部检查项合格、
 * 施工单置竣工(process 0→1)时发布;装修单工程线完工(1→2)由订阅方联动。
 * 经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "construction:order:{orderId}:accepted" 做去重锚点。
 */
public record ConstructionAcceptedEvent(Long constructionId, Long orgId, Long orderId,
        Long acceptanceId, Long operatorId) {
}
