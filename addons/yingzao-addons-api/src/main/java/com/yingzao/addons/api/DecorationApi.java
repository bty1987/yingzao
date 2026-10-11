package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 装修单域对插件开放的只读契约(实现在 addon-decoration,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供业务插件(如报价)在挂单时校验装修单归属并取展示快照,
 * 与 CustomerProfileApi 同一纪律:主数据唯一写点,跨插件只读契约。
 */
public interface DecorationApi {

    /** 装修单摘要(只读);不存在或不属本组织返回 empty。 */
    Optional<OrderSummary> queryOrder(long orgId, long orderId);

    /** 装修单摘要快照(label 为房屋展示快照,与主单 house_label 同源)。 */
    record OrderSummary(long orderId, String orderNo, String customerName, String houseLabel,
                        int type, int status, long designerId, long salesmanId) {
    }
}
