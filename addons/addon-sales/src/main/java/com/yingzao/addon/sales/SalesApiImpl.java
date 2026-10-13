package com.yingzao.addon.sales;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.SalesApi;

/**
 * SalesApi 只读契约实现(sales 侧):供工程域开工/完工销售款比例闸聚合进度,
 * 以及 addon-member 主材积分记分反查归属客户(销售单主数据只存客户名快照,
 * 归属客户经挂接装修单解析——两跳均走只读契约,禁直查他域表)。
 */
@Component
public class SalesApiImpl implements SalesApi {

    private final SalesOrderService orders;
    private final JdbcTemplate jdbc;
    private final DecorationApi decorations;

    public SalesApiImpl(SalesOrderService orders, JdbcTemplate jdbc, DecorationApi decorations) {
        this.orders = orders;
        this.jdbc = jdbc;
        this.decorations = decorations;
    }

    @Override
    public long collectedRatio(long orgId, long orderId) {
        return orders.collectedRatio(orgId, orderId);
    }

    @Override
    public Optional<SalesOrderRef> querySalesOrder(long orgId, long salesOrderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, order_no, order_id, customer_name from sales_order where id = ? and org_id = ?",
                salesOrderId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new SalesOrderRef(((Number) row.get("id")).longValue(),
                (String) row.get("order_no"), ((Number) row.get("order_id")).longValue(),
                (String) row.get("customer_name")));
    }

    @Override
    public int countUnfinishedOrders(long orgId, long orderId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from sales_order
                where org_id = ? and order_id = ? and status not in (2, 4, 5)
                """, Integer.class, orgId, orderId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<DecorationApi.CustomerRef> queryOrderCustomer(long orgId, long salesOrderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select order_id from sales_order where id = ? and org_id = ?", salesOrderId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long orderId = ((Number) rows.get(0).get("order_id")).longValue();
        return decorations.queryOrderCustomer(orgId, orderId);
    }
}
