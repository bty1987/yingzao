package com.yingzao.addons.api.event;

/**
 * 投诉处理完成领域事件(契约,发布方 addon-service):投诉标记已处理(→2)时发布;
 * 投诉回访联动任务由本域内部生成(不走事件);本事件供报表/通知消费方 V2 接入。
 * 经内核事件总线投递,eventKey = "service:complaint:{id}:handled" 幂等锚。
 */
public record ComplaintHandledEvent(Long complaintId, Long orgId, Long orderId, Long operatorId) {
}
