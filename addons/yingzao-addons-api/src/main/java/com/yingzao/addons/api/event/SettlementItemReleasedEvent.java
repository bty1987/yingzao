package com.yingzao.addons.api.event;

/**
 * 明细退回领域事件(契约,发布方 addon-balance):结算侧解绑明细时发布,上游据此回置自己的
 * 对账状态(accountStatus 语义)——替代老系统 balance 直写上游三字段的双向耦合。
 * 订阅方(上游插件)必须幂等,建议以 eventKey = "settle-item:{sourceAddon}:{sourceType}:{sourceItemId}:released"
 * 做去重锚点;action 协议键见常量。
 */
public record SettlementItemReleasedEvent(Long orgId, String sourceAddon, String sourceType,
                                          String sourceItemId, Long settlementId, String settlementNo,
                                          String action, String reason) {

    /** action 协议键:解绑退回上游。 */
    public static final String ACTION_UNBIND = "unbind";
}
