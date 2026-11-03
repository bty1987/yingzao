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

    /** C 端资金流水主材款投影数据源:含完结单(已付历史可见),剔除全退/倒单/废单,id 倒序。 */
    @Override
    public List<SalesOrderRef> listByDecorationOrder(long orgId, long orderId) {
        return jdbc.queryForList("""
                select id, code, order_id, customer_name from sales_order
                where org_id = ? and order_id = ? and status not in (2, 3, 4)
                order by id desc
                """, orgId, orderId).stream()
                .map(r -> new SalesOrderRef(((Number) r.get("id")).longValue(),
                        (String) r.get("code"), ((Number) r.get("order_id")).longValue(),
                        (String) r.get("customer_name")))
                .toList();
    }

    /** 主材开单业绩·月额(target 主材线实绩):创建日落当月,剔除废退单,含完结,厘。 */
    @Override
    public long materialSalesTotalByMonth(long orgId, String yearMonth) {
        Long total = jdbc.queryForObject("""
                select coalesce(sum(total), 0) from sales_order
                where org_id = ? and status not in (2, 3, 4)
                  and created_at >= ? and created_at < ? + interval 1 month
                """, Long.class, orgId, yearMonth + "-01", yearMonth + "-01");
        return total == null ? 0L : total;
    }

    /** 主材开单业绩·区间额(阶段实绩):created_at 闭区间,口径同月额。 */
    @Override
    public long materialSalesTotalBetween(long orgId, String beginDay, String endDay) {
        Long total = jdbc.queryForObject("""
                select coalesce(sum(total), 0) from sales_order
                where org_id = ? and status not in (2, 3, 4)
                  and created_at >= ? and created_at < ? + interval 1 day
                """, Long.class, orgId, beginDay, endDay);
        return total == null ? 0L : total;
    }
}
