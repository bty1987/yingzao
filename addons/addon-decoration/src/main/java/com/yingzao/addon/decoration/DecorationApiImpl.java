package com.yingzao.addon.decoration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.DecorationApi;

/**
 * 装修单域只读契约实现(消费方:addon-quotation 建报价挂单校验+展示快照);只读,不持业务写路径。
 */
@Component
public class DecorationApiImpl implements DecorationApi {

    private final JdbcTemplate jdbc;

    public DecorationApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
}
