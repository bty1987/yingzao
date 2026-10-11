package com.yingzao.addons.api.event;

/**
 * 预付款变动领域事件(契约,发布方 addon-payment):科目余额发生任何流水(收入/划扣/提现/退回)后发布。
 * 金额口径 = 厘,amount 恒正,方向看 flowType(1 收入 2 划扣 3 提现 4 退回);
 * balanceAfter 为操作后余额快照,账户恒满足 余额==Σ流水(对账红线)。
 * 经内核事件总线投递,订阅方必须幂等(eventKey = "advance-flow:{flowId}")。
 */
public record AdvancePaymentChangedEvent(Long accountId, Long orgId, String subjectCode, String refType,
        String refId, Integer flowType, Long amount, Long balanceAfter,
        String bizAddon, String bizType, String bizId) {
}
