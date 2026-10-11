package com.yingzao.addons.api.event;

/**
 * 施工开工领域事件(契约,发布方 addon-construction):放线成功或显式补录点火时发布——
 * 施工单创建 + 节点排期快照生成,装修单工程线随之开工。
 * 经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "construction:order:{orderId}:started" 做去重锚点。
 */
public record ConstructionStartedEvent(Long constructionId, Long orgId, Long orderId, Long operatorId) {
}
