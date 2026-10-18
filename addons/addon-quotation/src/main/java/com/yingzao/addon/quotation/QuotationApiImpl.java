package com.yingzao.addon.quotation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.QuotationApi;

/**
 * 报价域只读契约实现(消费方:decoration 预签门槛等);只读,不持业务写路径。
 */
@Component
public class QuotationApiImpl implements QuotationApi {

    private final JdbcTemplate jdbc;

    public QuotationApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasLockedQuotation(long orgId, long orderId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from quotation_quotation
                where org_id = ? and order_id = ? and status = ?
                """, Integer.class, orgId, orderId, QuotationService.STATUS_LOCKED);
        return count != null && count > 0;
    }

    /** 默认报价挂的定材包(未挂/无默认报价 → empty=领料不拦)。 */
    @Override
    public Optional<Long> designatedMaterialPackageId(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select material_package_id from quotation_quotation
                where org_id = ? and order_id = ? and is_default = 1 limit 1
                """, orgId, orderId);
        if (rows.isEmpty() || rows.get(0).get("material_package_id") == null) {
            return Optional.empty();
        }
        return Optional.of(((Number) rows.get(0).get("material_package_id")).longValue());
    }

    /** 未确认差量单存在性(0 草稿/1 已提交/2 已付款均算;3 已确认终态不算)。 */
    @Override
    public boolean hasUnconfirmedAddReduce(long orgId, long orderId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from quotation_add_reduce
                where org_id = ? and order_id = ? and status in (0, 1, 2)
                """, Integer.class, orgId, orderId);
        return count != null && count > 0;
    }

    @Override
    public Optional<QuotationSummary> queryDefaultQuotation(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, total_price, discount from quotation_quotation
                where org_id = ? and order_id = ? and is_default = 1 limit 1
                """, orgId, orderId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new QuotationSummary(((Number) row.get("id")).longValue(),
                (String) row.get("name"), ((Number) row.get("total_price")).longValue(),
                ((Number) row.get("discount")).intValue()));
    }
}
