package com.yingzao.addon.basedata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.GoodsApi;

/**
 * GoodsApi 只读契约实现(basedata 侧):供库存/销售/报价等插件校验商品归属并取展示快照,
 * 禁止消费方直查 basedata 表。SKU 属性停用态按"不存在"处理(停用属性不可再用于新建库存行)。
 */
@Component
public class GoodsApiImpl implements GoodsApi {

    private final JdbcTemplate jdbc;

    public GoodsApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GoodsSummary> queryGoods(long orgId, long goodsId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, no, name, unit, type, selling, status
                from basedata_goods where id = ? and org_id = ?
                """, goodsId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new GoodsSummary(goodsId, (String) row.get("no"), (String) row.get("name"),
                (String) row.get("unit"), ((Number) row.get("type")).intValue(),
                ((Number) row.get("selling")).longValue(), ((Number) row.get("status")).intValue()));
    }

    @Override
    public Optional<SkuAttrSummary> querySkuAttr(long orgId, long goodsId, long attrId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, attr_type, value, status from basedata_goods_sku_attr
                where id = ? and goods_id = ? and org_id = ? and status = 0
                """, attrId, goodsId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new SkuAttrSummary(attrId, ((Number) row.get("attr_type")).intValue(),
                (String) row.get("value"), ((Number) row.get("status")).intValue()));
    }
}
