package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 报价域对插件开放的只读契约(实现在 addon-quotation,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。典型消费方:addon-decoration 预签门槛
 * 「存在已锁定(定稿)报价」;后续签约口径基准/对账展示读默认报价摘要。
 */
public interface QuotationApi {

    /** 该装修单下是否存在已锁定(status=1)报价。 */
    boolean hasLockedQuotation(long orgId, long orderId);

    /** 默认报价摘要(只读);无默认报价返回 empty。totalPrice 厘,discount 万分比(10000=不打折)。 */
    Optional<QuotationSummary> queryDefaultQuotation(long orgId, long orderId);

    /** 默认报价摘要快照。 */
    record QuotationSummary(long quotationId, String name, long totalPrice, int discount) {
    }

    /**
     * 该装修单默认报价所挂的定材包(领料定材校验的定额来源;B 类#7)。未挂包/无默认报价返回 empty
     * ——调用方语义=不拦(老:报价无定额单即返回 0 放行)。
     */
    java.util.Optional<Long> designatedMaterialPackageId(long orgId, long orderId);
}
