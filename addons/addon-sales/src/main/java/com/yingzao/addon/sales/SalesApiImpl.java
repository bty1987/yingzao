package com.yingzao.addon.sales;

import org.springframework.stereotype.Component;

import com.yingzao.addons.api.SalesApi;

/**
 * SalesApi 只读契约实现(sales 侧):供工程域开工/完工销售款比例闸聚合进度,
 * 禁止消费方直查销售表与款项表。
 */
@Component
public class SalesApiImpl implements SalesApi {

    private final SalesOrderService orders;

    public SalesApiImpl(SalesOrderService orders) {
        this.orders = orders;
    }

    @Override
    public long collectedRatio(long orgId, long orderId) {
        return orders.collectedRatio(orgId, orderId);
    }
}
