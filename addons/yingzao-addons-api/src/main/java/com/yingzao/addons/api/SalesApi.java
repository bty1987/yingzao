package com.yingzao.addons.api;

/**
 * 销售域对插件开放的只读契约(实现在 addon-sales,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供工程域开工/完工比例闸聚合装修单名下销售款进度,
 * 与 DecorationApi/QuotationApi 同一纪律:跨插件只读契约。
 */
public interface SalesApi {

    /**
     * 装修单名下全部销售单的销售款收款比例(千分比 ‰,0-1000):
     * Σ(已收+已确认款项) / Σ(未作废应收款项)。无销售单或无应收返回 0。
     * 消费方:施工开工/完工销售款闸(stacheck/fincheck),禁直查销售表与款项表。
     */
    long collectedRatio(long orgId, long orderId);
}
