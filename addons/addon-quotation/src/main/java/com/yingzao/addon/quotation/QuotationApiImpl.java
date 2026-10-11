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
