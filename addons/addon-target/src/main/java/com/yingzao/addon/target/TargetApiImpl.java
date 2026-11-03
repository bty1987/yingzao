package com.yingzao.addon.target;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.TargetApi;

/**
 * 经营目标域只读契约实现(消费方:dashboard 工作台族 W2 主材销售工作台);
 * 只读投影,不持业务写路径,与 DecorationApiImpl/SalesApiImpl 同一纪律。
 */
@Component
public class TargetApiImpl implements TargetApi {

    private final JdbcTemplate jdbc;

    public TargetApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlanView> planOfMonth(long orgId, long orgNode, String yearMonth, String line) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.id, p.org_node, o.name as node_name, p.amount, p.status
                from target_plan p left join organization o on o.id = p.org_node
                where p.org_id = ? and p.org_node = ? and p.ym = ? and p.line = ? and p.status = 0
                order by p.id limit 1
                """, orgId, orgNode, yearMonth, line);
        return rows.isEmpty() ? Optional.empty() : Optional.of(view(rows.get(0)));
    }

    @Override
    public List<PlanView> listPlansOfMonth(long orgId, String yearMonth, String line) {
        return jdbc.queryForList("""
                select p.id, p.org_node, o.name as node_name, p.amount, p.status
                from target_plan p left join organization o on o.id = p.org_node
                where p.org_id = ? and p.ym = ? and p.line = ?
                order by p.org_node, p.id
                """, orgId, yearMonth, line).stream().map(this::view).toList();
    }

    private PlanView view(Map<String, Object> r) {
        return new PlanView(((Number) r.get("id")).longValue(),
                ((Number) r.get("org_node")).longValue(),
                (String) r.get("node_name"),
                ((Number) r.get("amount")).longValue(),
                ((Number) r.get("status")).intValue());
    }
}
