package com.yingzao.addons.api.event;

/**
 * 施工节点状态领域事件(契约,发布方 addon-construction):节点排期状态变更后发布。
 * status 状态机:0 初始 → 1 开工 → 2 完工,3 = 开放工费旁路(1→3,3→2);
 * action 为业务动作协议键(begin/finish/openSalary/checkout)。
 * 经内核事件总线投递,订阅方必须幂等,
 * 建议以 eventKey = "construction:schedule:{scheduleId}:{action}" 做去重锚点。
 */
public record ConstructionScheduleStatusChangedEvent(Long constructionId, Long orgId, Long orderId,
        Long scheduleId, String nodeName, Integer fromStatus, Integer toStatus,
        String action, Long operatorId) {
}
