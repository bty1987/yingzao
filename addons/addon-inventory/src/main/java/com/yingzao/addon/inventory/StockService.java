package com.yingzao.addon.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.GoodsApi;
import com.yingzao.core.web.BizException;

/**
 * 实时库存记账面:全域唯一真守卫 = 原子条件 UPDATE(入库自增/出库 num>=:num 扣减,
 * 老系统 2026-10-01 才修上的并发正确性孤本,保留并补唯一键防首插分裂)。
 * 商品/色规经 GoodsApi 契约校验并落名称快照(禁直查 basedata);数量千分位恒正
 * (修老恒假守卫 size()<0 与负数击穿);区分 310021 库存行不存在 / 310022 库存不足。
 */
@Service
public class StockService {

    public static final int CODE_NUM_POSITIVE = 310031;
    public static final int CODE_GOODS_NOT_FOUND = 310020;
    public static final int CODE_STOCK_ROW_MISSING = 310021;
    public static final int CODE_STOCK_INSUFFICIENT = 310022;
    public static final int CODE_SKU_NOT_FOUND = 310023;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final GoodsApi goods;

    public StockService(JdbcTemplate jdbc, MessageSource messages, GoodsApi goods) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.goods = goods;
    }

    /** 入库记账:原子自增,0 行 = 无库存行 → 校验后建档(并发首插撞唯一键回退自增)。 */
    @Transactional
    public void in(long orgId, long goodsId, long colorId, long formatId, long warehouseId, long num) {
        require(num > 0, CODE_NUM_POSITIVE, "inventory.num.positive");
        int updated = jdbc.update("""
                update inventory_stock set num = num + ?
                where org_id = ? and goods_id = ? and color_id = ? and format_id = ? and warehouse_id = ?
                """, num, orgId, goodsId, colorId, formatId, warehouseId);
        if (updated > 0) {
            return;
        }
        Snapshot snapshot = snapshot(orgId, goodsId, colorId, formatId);
        requireWarehouse(orgId, warehouseId);
        try {
            jdbc.update("""
                    insert into inventory_stock(org_id, goods_id, color_id, format_id, goods_name,
                        color_name, format_name, warehouse_id, num)
                    values (?,?,?,?,?,?,?,?,?)
                    """, orgId, goodsId, colorId, formatId, snapshot.goodsName(),
                    snapshot.colorName(), snapshot.formatName(), warehouseId, num);
        } catch (DuplicateKeyException e) {
            jdbc.update("""
                    update inventory_stock set num = num + ?
                    where org_id = ? and goods_id = ? and color_id = ? and format_id = ? and warehouse_id = ?
                    """, num, orgId, goodsId, colorId, formatId, warehouseId);
        }
    }

    /** 出库记账:单语句原子扣减,0 行 = 库存行不存在或存量不足(防并发超扣)。 */
    @Transactional
    public void out(long orgId, long goodsId, long colorId, long formatId, long warehouseId, long num) {
        require(num > 0, CODE_NUM_POSITIVE, "inventory.num.positive");
        int updated = jdbc.update("""
                update inventory_stock set num = num - ?
                where org_id = ? and goods_id = ? and color_id = ? and format_id = ? and warehouse_id = ?
                  and num >= ?
                """, num, orgId, goodsId, colorId, formatId, warehouseId, num);
        if (updated > 0) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select goods_name from inventory_stock
                where org_id = ? and goods_id = ? and color_id = ? and format_id = ? and warehouse_id = ?
                """, orgId, goodsId, colorId, formatId, warehouseId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_STOCK_ROW_MISSING, msg("inventory.stock.rowMissing"));
        }
        throw new BizException(CODE_STOCK_INSUFFICIENT,
                msgArgs("inventory.stock.insufficient", rows.get(0).get("goods_name")));
    }

    /** 库存查询(按授权仓库过滤,无授权 = 空列表,老语义保留;admin 亦须显式授权)。 */
    public Map<String, Object> page(long orgId, long userId, Long warehouseId, String keyword,
            int pageNo, int size) {
        List<Long> warehouseIds = authorizedWarehouses(orgId, userId);
        StringBuilder where = new StringBuilder(" where s.org_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (warehouseIds.isEmpty()) {
            where.append(" and 1 = 0");
        } else {
            where.append(" and s.warehouse_id in (");
            for (int i = 0; i < warehouseIds.size(); i++) {
                where.append(i == 0 ? "?" : ",?");
                args.add(warehouseIds.get(i));
            }
            where.append(")");
        }
        if (warehouseId != null) {
            where.append(" and s.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (notBlank(keyword)) {
            where.append(" and (s.goods_name like ? or w.name like ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from inventory_stock s join inventory_warehouse w on w.id = s.warehouse_id"
                        + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(Math.min(Math.max(size, 1), 100));
        allArgs.add((Math.max(pageNo, 1) - 1) * Math.min(Math.max(size, 1), 100));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.goods_id, s.goods_name, s.color_name, s.format_name,
                       s.warehouse_id, w.name as warehouse_name, s.num, s.updated_at
                from inventory_stock s join inventory_warehouse w on w.id = s.warehouse_id
                """ + where + " order by s.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public List<Long> authorizedWarehouses(long orgId, long userId) {
        return jdbc.queryForList(
                "select warehouse_id from inventory_warehouse_user where org_id = ? and user_id = ?",
                Long.class, orgId, userId);
    }

    // ---- 校验与快照 ----

    public record Snapshot(long goodsId, String goodsName, long colorId, String colorName,
                           long formatId, String formatName) {
    }

    /** 商品+色规校验并取展示快照(色规可空 0;经 GoodsApi 契约,停用属性按不存在处理)。 */
    public Snapshot snapshot(long orgId, long goodsId, long colorId, long formatId) {
        GoodsApi.GoodsSummary summary = goods.queryGoods(orgId, goodsId)
                .orElseThrow(() -> new BizException(CODE_GOODS_NOT_FOUND, msg("inventory.goods.notFound")));
        String colorName = "";
        if (colorId > 0) {
            colorName = goods.querySkuAttr(orgId, goodsId, colorId)
                    .filter(a -> a.attrType() == 0)
                    .map(GoodsApi.SkuAttrSummary::value)
                    .orElseThrow(() -> new BizException(CODE_SKU_NOT_FOUND, msg("inventory.sku.notFound")));
        }
        String formatName = "";
        if (formatId > 0) {
            formatName = goods.querySkuAttr(orgId, goodsId, formatId)
                    .filter(a -> a.attrType() == 1)
                    .map(GoodsApi.SkuAttrSummary::value)
                    .orElseThrow(() -> new BizException(CODE_SKU_NOT_FOUND, msg("inventory.sku.notFound")));
        }
        return new Snapshot(goodsId, summary.name(), colorId, colorName, formatId, formatName);
    }

    public void requireWarehouse(long orgId, long warehouseId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from inventory_warehouse
                where id = ? and org_id = ? and status = 0
                """, Integer.class, warehouseId, orgId);
        require(count != null && count > 0, WarehouseService.CODE_NOT_FOUND, "inventory.warehouse.notFound");
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
