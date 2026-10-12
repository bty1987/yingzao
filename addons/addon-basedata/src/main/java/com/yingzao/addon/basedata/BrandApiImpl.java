package com.yingzao.addon.basedata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.BrandApi;

/**
 * BrandApi 只读实现:供厂商品牌关联等写点校验品牌存在并取快照。
 * 只读、零业务知识;品牌是全局公共字典(无 org 维度)。
 */
@Component
public class BrandApiImpl implements BrandApi {

    private final JdbcTemplate jdbc;

    public BrandApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BrandSummary> queryBrand(long brandId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, no, name, status from basedata_brand where id = ?", brandId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new BrandSummary(((Number) row.get("id")).longValue(),
                (String) row.get("no"), (String) row.get("name"),
                ((Number) row.get("status")).intValue()));
    }
}
