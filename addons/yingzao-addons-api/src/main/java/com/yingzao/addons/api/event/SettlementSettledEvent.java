package com.yingzao.addons.api.event;

/**
 * 结算单已结算领域事件(契约,发布方 addon-balance):结算动作完成(资金头生成,应付确定)后发布,
 * 上游插件/通知中心据此落通知或回写展示面。金额 = 应付总额(厘,已扣折扣)。
 * 订阅方必须幂等,建议以 eventKey = "balance:settlement:{settlementId}:settled" 做去重锚点。
 */
public record SettlementSettledEvent(Long settlementId, Long orgId, String no, String settlementType,
                                     String brandName, String supplier, String supplierName,
                                     Long amount, Long operatorId) {
}
