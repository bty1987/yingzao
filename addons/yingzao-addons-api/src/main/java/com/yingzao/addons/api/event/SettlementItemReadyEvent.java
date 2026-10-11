package com.yingzao.addons.api.event;

/**
 * 明细可结算领域事件(契约,发布方=上游业务插件:addon-sales/addon-quotation 等):
 * 上游明细完成己方结算口径(金额终值确定)后发布,balance 监听后按「品牌+归属人+等级」聚桶
 * 落快照行——事件携带金额快照,替代老系统跨域 JOIN 动态聚合(金额漂移根源)与跨模块服务直调。
 * 订阅方(addon-balance)幂等:同一 source 三元组重复发布,金额一致则幂等跳过,不一致按上游修正更新。
 * 建议以 eventKey = "settle-item:{sourceAddon}:{sourceType}:{sourceItemId}:ready" 做去重锚点。
 * 金额口径 = 厘(×1000 定点整数);income 应收 / cost 让利国补 / payment 应付厂商(退货行可为负,
 * 正负由快照生成侧完成取负规则,balance 只做加总)。
 */
public record SettlementItemReadyEvent(Long orgId, String sourceAddon, String sourceType,
                                       String sourceItemId, Long brandId, String brandName,
                                       String supplier, String supplierName, String grade,
                                       Long qty, Long price, Long income, Long cost, Long payment,
                                       String memo) {
}
