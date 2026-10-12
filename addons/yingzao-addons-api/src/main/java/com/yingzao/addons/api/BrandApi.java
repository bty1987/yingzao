package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 品牌字典对插件开放的只读契约(实现在 addon-basedata,品牌是全局公共字典)。
 * 供厂商品牌关联/选材/结算等写点校验品牌存在,与 GoodsApi 同一快照纪律。
 */
public interface BrandApi {

    /** 品牌摘要(只读);不存在返回 empty(status 0 启用 / 1 停用)。 */
    Optional<BrandSummary> queryBrand(long brandId);

    record BrandSummary(long brandId, String no, String name, int status) {
    }
}
