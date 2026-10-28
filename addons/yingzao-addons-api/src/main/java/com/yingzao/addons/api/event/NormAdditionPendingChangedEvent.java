package com.yingzao.addons.api.event;

/**
 * 附加项待审集合变化事件(契约,发布方 addon-quotation 定额子域):附加确认线
 * 登记/确认/驳回/删除导致某节点待审集合变化时发布——替代老系统跨域直写
 * construction schedule 表 additem_status 的违例(事件回写惯例);
 * 首个消费方 addon-construction(排期行打「待审」徽章)。
 * pendingCount = 该单该节点当前待确认(status=0)附加行数;经内核事件总线投递,
 * 消费方以查询期对账为准,建议以 eventKey = "norm:addition:{orderId}:{nodeId}:pending" 做去重锚点。
 */
public record NormAdditionPendingChangedEvent(Long orgId, Long orderId, Long nodeId,
        Long pendingCount, Long operatorId) {
}
