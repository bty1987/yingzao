package com.yingzao.addon.decoration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.PaymentApi;

/**
 * 装修单域只读契约实现(消费方:addon-quotation 建报价挂单校验+展示快照);只读,不持业务写路径。
 */
@Component
public class DecorationApiImpl implements DecorationApi {

    private final JdbcTemplate jdbc;
    private final PaymentApi payments;

    public DecorationApiImpl(JdbcTemplate jdbc,
            @org.springframework.context.annotation.Lazy PaymentApi payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @Override
    public Optional<OrderSummary> queryOrder(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_no, customer_name, house_label, type, status, project_status,
                       designer_id, salesman_id
                from decoration_order where id = ? and org_id = ?
                """, orderId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new OrderSummary(((Number) row.get("id")).longValue(),
                (String) row.get("order_no"), (String) row.get("customer_name"),
                (String) row.get("house_label"), ((Number) row.get("type")).intValue(),
                ((Number) row.get("status")).intValue(),
                ((Number) row.get("project_status")).intValue(),
                ((Number) row.get("designer_id")).longValue(),
                ((Number) row.get("salesman_id")).longValue()));
    }

    /** C 端「我的装修单」(只读;废单 5 不返回,登记倒序)。 */
    @Override
    public java.util.List<OrderBrief> listByCustomer(long orgId, long customerId) {
        return jdbc.queryForList("""
                select id, order_no, house_label, status, project_status
                from decoration_order where org_id = ? and customer_id = ? and status != 5
                order by id desc
                """, orgId, customerId).stream()
                .map(r -> new OrderBrief(((Number) r.get("id")).longValue(),
                        (String) r.get("order_no"), (String) r.get("house_label"),
                        ((Number) r.get("status")).intValue(),
                        ((Number) r.get("project_status")).intValue()))
                .toList();
    }

    /** 签约实绩月合计(只读聚合;target 消费):当月首签单的签约拆分款应收合计(未作废)。 */
    @Override
    public long contractSignedTotalByMonth(long orgId, String yearMonth) {
        List<Long> ids = jdbc.queryForList(
                "select id from decoration_order where org_id = ? and status in (3, 4, 7, 8)"
                        + " and date_format(contract_time, '%Y-%m') = ?",
                Long.class, orgId, yearMonth);
        long total = 0;
        for (Long orderId : ids) {
            for (PaymentApi.BillSummary b : payments.listBills(orgId, "decoration", "order",
                    String.valueOf(orderId))) {
                if (b.direction() == 0 && b.status() != 4) {
                    total += b.amount();
                }
            }
        }
        return total;
    }

    @Override
    public Optional<CustomerRef> queryOrderCustomer(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select customer_id, customer_name from decoration_order where id = ? and org_id = ?",
                orderId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new CustomerRef(((Number) row.get("customer_id")).longValue(),
                (String) row.get("customer_name")));
    }
}
