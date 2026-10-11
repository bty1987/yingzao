package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 商品主档对插件开放的只读契约(实现在 addon-basedata,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。库存/报价/销售等域建档时校验商品并取名称快照
 * (展示名一律建档快照,禁跨插件直查)。与 CustomerProfileApi/DecorationApi 同一纪律。
 */
public interface GoodsApi {

    /** 商品摘要(只读);不存在或不属本组织返回 empty。selling = 标准售价(厘)。 */
    Optional<GoodsSummary> queryGoods(long orgId, long goodsId);

    /** SKU 属性(颜色/规格);不存在、不属于该商品或已停用返回 empty。 */
    Optional<SkuAttrSummary> querySkuAttr(long orgId, long goodsId, long attrId);

    record GoodsSummary(long goodsId, String no, String name, String unit, int type,
                        long selling, int status) {
    }

    /** attrType:0 颜色 / 1 规格。 */
    record SkuAttrSummary(long attrId, int attrType, String value, int status) {
    }
}
