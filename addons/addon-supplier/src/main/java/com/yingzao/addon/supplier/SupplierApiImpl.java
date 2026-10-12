package com.yingzao.addon.supplier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.SupplierApi;

/**
 * SupplierApi 只读实现:供 inventory(外辅挂厂商)/sales/balance(结算归口)等业务插件
 * 校验厂商存在且启用并取展示快照。只读、零业务知识;快照纪律:落单存名称,禁跨插件直查。
 */
@Component
public class SupplierApiImpl implements SupplierApi {

    private final JdbcTemplate jdbc;

    public SupplierApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SupplierSummary> querySupplier(long orgId, long supplierId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, no, name, status from supplier_supplier where id = ? and org_id = ?",
                supplierId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(toSummary(row));
    }

    @Override
    public List<SupplierSummary> queryBrandSuppliers(long orgId, long brandId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.no, s.name, s.status
                from supplier_brand b
                join supplier_supplier s on s.id = b.supplier_id
                where b.org_id = ? and b.brand_id = ? and s.status = ?
                order by b.is_primary desc, s.id
                """, orgId, brandId, SupplierService.STATUS_ENABLED);
        return rows.stream().map(this::toSummary).toList();
    }

    @Override
    public boolean checkActive(long orgId, long supplierId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from supplier_supplier
                where id = ? and org_id = ? and status = ?
                """, Integer.class, supplierId, orgId, SupplierService.STATUS_ENABLED);
        return count != null && count > 0;
    }

    private SupplierSummary toSummary(Map<String, Object> row) {
        return new SupplierSummary(((Number) row.get("id")).longValue(),
                (String) row.get("no"), (String) row.get("name"),
                ((Number) row.get("status")).intValue());
    }
}
