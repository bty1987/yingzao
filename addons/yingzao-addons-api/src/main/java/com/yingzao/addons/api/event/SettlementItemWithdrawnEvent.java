package com.yingzao.addons.api.event;

/**
 * 明细退结算领域事件(契约,发布方=上游业务插件):上游明细作废/撤销己方结算口径时发布,
 * balance 将该明细从「未锁定」结算单中摘除并重算金额;结算单已锁定/已结算则消费失败转死信
 * (可见且可 requeue,上游须走线下对账)。
 * 建议以 eventKey = "settle-item:{sourceAddon}:{sourceType}:{sourceItemId}:withdrawn" 做去重锚点。
 */
public record SettlementItemWithdrawnEvent(Long orgId, String sourceAddon, String sourceType,
                                           String sourceItemId, String reason) {
}
