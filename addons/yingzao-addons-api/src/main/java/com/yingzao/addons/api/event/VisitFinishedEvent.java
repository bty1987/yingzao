package com.yingzao.addons.api.event;

/**
 * 回访完成领域事件(契约,发布方 addon-service):回访任务提交完成(→1)时发布;
 * rating 1-5;无法完成(→2)不发本事件(原因在案,不构成满意度样本)。
 * 经内核事件总线投递,eventKey = "service:visit:{id}:finished" 幂等锚。
 */
public record VisitFinishedEvent(Long taskId, Long orgId, Long orderId, Integer rating,
        Long sourceBizId, Long operatorId) {
}
