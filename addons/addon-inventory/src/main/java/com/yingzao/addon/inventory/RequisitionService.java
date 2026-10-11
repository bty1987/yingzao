package com.yingzao.addon.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.ConstructionAccessoryApi;
import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.GoodsApi;
import com.yingzao.core.web.BizException;

/**
 * 领料单(老 accessories 正名 material requisition):工程辅料领用,额度双闸经
 * ConstructionAccessoryApi 契约同步调用(锁定闸/总额闸/per-gid 闸在施工域一次完成,
 * 本域零直写施工表);金额服务端重算(修老信任前端 total + 撤销重算的口径漂移);
 * 售后单(type=1)跳过全部额度联动(行业惯例:售后用料不占工程额度);
 * 状态机 0初始→1预出→2已出库→6已到货,撤销/拒绝仅初始态(老 5 退回死状态、
 * 7 审核无守卫态不迁,审核线随 V1.1 走内核轻量审批);退回(AR)= 已出库数量内原子回冲。
 */
@Service
public class RequisitionService {

    public static final int CODE_NOT_FOUND = 310001;
    public static final int CODE_BAD_STATE = 310002;
    public static final int CODE_PARAM = 310003;
    public static final int CODE_RETURN_OVER = 310043;
    public static final int CODE_DECORATION_NOT_FOUND = 310044;
    public static final int CODE_CLOSED = 310041;

    public static final int TYPE_COMMON = 0;
    public static final int TYPE_POSTSALE = 1;

    public static final int ST_INIT = 0;
    public static final int ST_PREOUT = 1;
    public static final int ST_OUTBOUND = 2;
    public static final int ST_CANCELLED = 3;
    public static final int ST_REFUSED = 4;
    public static final int ST_ARRIVED = 6;

    public record ItemCmd(Long goodsId, Long colorId, Long formatId, Long warehouseId, Long num) {
    }

    public record ReturnLineCmd(Long itemId, Long num) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final StockService stocks;
    private final ManifestService manifests;
    private final ConstructionAccessoryApi accessoryQuotas;
    private final DecorationApi decorations;
    private final GoodsApi goods;

    public RequisitionService(JdbcTemplate jdbc, MessageSource messages, StockService stocks,
            ManifestService manifests, ConstructionAccessoryApi accessoryQuotas,
            DecorationApi decorations, GoodsApi goods) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.stocks = stocks;
        this.manifests = manifests;
        this.accessoryQuotas = accessoryQuotas;
        this.decorations = decorations;
        this.goods = goods;
    }

    @Transactional
    public long create(long orgId, Long orderId, Integer type, List<ItemCmd> items, String comment,
            String fetchTimeText, long operatorId) {
        require(orderId != null && type != null && (type == TYPE_COMMON || type == TYPE_POSTSALE),
                CODE_PARAM, "inventory.param.invalid");
        require(items != null && !items.isEmpty(), ManifestService.CODE_EMPTY_ITEMS,
                "inventory.items.empty");
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_DECORATION_NOT_FOUND,
                        msg("inventory.requisition.decorationNotFound")));
        long total = 0;
        List<DerivedRow> rows = new ArrayList<>();
        for (ItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null && cmd.warehouseId() != null
                    && cmd.num() != null, CODE_PARAM, "inventory.param.invalid");
            require(cmd.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            stocks.requireWarehouse(orgId, cmd.warehouseId());
            StockService.Snapshot snapshot = stocks.snapshot(orgId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()));
            long selling = goods.queryGoods(orgId, cmd.goodsId())
                    .orElseThrow(() -> new BizException(StockService.CODE_GOODS_NOT_FOUND,
                            msg("inventory.goods.notFound")))
                    .selling();
            long lineTotal = selling * cmd.num() / 1000;
            total += lineTotal;
            rows.add(new DerivedRow(snapshot, cmd.warehouseId(), cmd.num(), selling, lineTotal));
        }
        jdbc.update("""
                insert into inventory_requisition(org_id, code, order_id, order_no, house_label, type,
                    status, total, comment, fetch_time, created_by, created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, nextCode(orgId), orderId, order.orderNo(), order.houseLabel(), type,
                ST_INIT, total, blankToNull(comment), parseTime(fetchTimeText), operatorId,
                displayName(operatorId));
        long requisitionId = jdbc.queryForObject("select last_insert_id()", Long.class);
        String code = jdbc.queryForObject("select code from inventory_requisition where id = ?",
                String.class, requisitionId);
        int sort = 0;
        for (DerivedRow row : rows) {
            sort++;
            jdbc.update("""
                    insert into inventory_requisition_item(org_id, requisition_id, goods_id, color_id,
                        format_id, goods_name, color_name, format_name, warehouse_id, warehouse_name,
                        num, selling, total, sort)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, requisitionId, row.snapshot().goodsId(), row.snapshot().colorId(),
                    row.snapshot().formatId(), row.snapshot().goodsName(), row.snapshot().colorName(),
                    row.snapshot().formatName(), row.warehouseId(),
                    warehouseName(orgId, row.warehouseId()), row.num(), row.selling(),
                    row.lineTotal(), sort);
        }
        if (type == TYPE_COMMON) {
            // 工程额度双闸(锁定/总额/per-gid)在施工域一次完成,同事务原子
            accessoryQuotas.consume(new ConstructionAccessoryApi.ConsumeCmd(orgId, orderId,
                    requisitionId, code, toContractItems(rows), total, operatorId));
        }
        return requisitionId;
    }

    /** 预出:0→1。 */
    @Transactional
    public void preout(long orgId, long requisitionId, long operatorId) {
        Map<String, Object> row = load(orgId, requisitionId);
        require(((Number) row.get("status")).intValue() == ST_INIT, CODE_BAD_STATE,
                "inventory.state.illegal");
        jdbc.update("""
                update inventory_requisition set status = ? where id = ? and status = ?
                """, ST_PREOUT, requisitionId, ST_INIT);
    }

    /** 出库:{0,1}→2:整单一次出库(老语义),库存原子扣减 + AS 货单派生(落单即确认)。 */
    @Transactional
    public void outbound(long orgId, long requisitionId, long operatorId) {
        Map<String, Object> row = load(orgId, requisitionId);
        int status = ((Number) row.get("status")).intValue();
        require(status == ST_INIT || status == ST_PREOUT, CODE_BAD_STATE, "inventory.state.illegal");
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, goods_id, color_id, format_id, goods_name, color_name, format_name,
                       warehouse_id, num, selling
                from inventory_requisition_item where requisition_id = ? order by sort
                """, requisitionId);
        List<ManifestService.DerivedItem> derived = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long goodsId = ((Number) item.get("goods_id")).longValue();
            long colorId = ((Number) item.get("color_id")).longValue();
            long formatId = ((Number) item.get("format_id")).longValue();
            long warehouseId = ((Number) item.get("warehouse_id")).longValue();
            long num = ((Number) item.get("num")).longValue();
            long selling = ((Number) item.get("selling")).longValue();
            StockService.Snapshot snapshot = new StockService.Snapshot(goodsId,
                    (String) item.get("goods_name"), colorId, text(item.get("color_name")),
                    formatId, text(item.get("format_name")));
            derived.add(new ManifestService.DerivedItem(snapshot, null, warehouseId, num, selling,
                    null, null, ((Number) item.get("id")).longValue(), null, null));
        }
        manifests.createDerived(orgId, ManifestService.TYPE_AS,
                ((Number) row.get("order_id")).longValue(), ManifestService.SOURCE_REQUISITION,
                requisitionId, derived, operatorId);
        // 整单一次出库:明细 outbound_num 一次性落账(退回闸的守卫基线)
        jdbc.update("update inventory_requisition_item set outbound_num = num where requisition_id = ?",
                requisitionId);
        jdbc.update("""
                update inventory_requisition set status = ?, outbound_at = now()
                where id = ? and status = ?
                """, ST_OUTBOUND, requisitionId, status);
    }

    /** 到货:2→6。 */
    @Transactional
    public void arrive(long orgId, long requisitionId, long operatorId) {
        Map<String, Object> row = load(orgId, requisitionId);
        require(((Number) row.get("status")).intValue() == ST_OUTBOUND, CODE_BAD_STATE,
                "inventory.state.illegal");
        jdbc.update("""
                update inventory_requisition set status = ?, arrive_at = now()
                where id = ? and status = ?
                """, ST_ARRIVED, requisitionId, ST_OUTBOUND);
    }

    /** 撤销/拒绝(仅初始态):售后单零联动,普通单按保存金额原样回退额度(契约 refund)。 */
    @Transactional
    public void close(long orgId, long requisitionId, boolean refuse, long operatorId) {
        Map<String, Object> row = load(orgId, requisitionId);
        require(((Number) row.get("status")).intValue() == ST_INIT, CODE_CLOSED,
                "inventory.requisition.closed");
        int type = ((Number) row.get("type")).intValue();
        long total = ((Number) row.get("total")).longValue();
        int status = refuse ? ST_REFUSED : ST_CANCELLED;
        jdbc.update("""
                update inventory_requisition set status = ?, close_at = now() where id = ? and status = ?
                """, status, requisitionId, ST_INIT);
        if (type == TYPE_COMMON) {
            accessoryQuotas.refund(new ConstructionAccessoryApi.RefundCmd(orgId,
                    ((Number) row.get("order_id")).longValue(), requisitionId,
                    (String) row.get("code"), itemsFromDb(requisitionId), total, operatorId));
        }
    }

    /** 退回(AR):已出库数量内原子回冲 + 入库回补 + 普通单额度退回;领料单状态不变(老语义)。 */
    @Transactional
    public long returnGoods(long orgId, long requisitionId, List<ReturnLineCmd> lines,
            long operatorId) {
        Map<String, Object> row = load(orgId, requisitionId);
        int status = ((Number) row.get("status")).intValue();
        require(status == ST_OUTBOUND || status == ST_ARRIVED, CODE_BAD_STATE,
                "inventory.state.illegal");
        require(lines != null && !lines.isEmpty(), ManifestService.CODE_EMPTY_ITEMS,
                "inventory.items.empty");
        int type = ((Number) row.get("type")).intValue();
        long refundTotal = 0;
        List<ManifestService.DerivedItem> derived = new ArrayList<>();
        List<ConstructionAccessoryApi.Item> refundItems = new ArrayList<>();
        for (ReturnLineCmd line : lines) {
            require(line != null && line.itemId() != null && line.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(line.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            int updated = jdbc.update("""
                    update inventory_requisition_item
                    set returned_num = returned_num + ?
                    where id = ? and org_id = ? and requisition_id = ?
                      and returned_num + ? <= outbound_num
                    """, line.num(), line.itemId(), orgId, requisitionId, line.num());
            require(updated > 0, CODE_RETURN_OVER, "inventory.requisition.returnOver");
            Map<String, Object> item = jdbc.queryForMap("""
                    select goods_id, color_id, format_id, goods_name, color_name, format_name,
                           warehouse_id, selling
                    from inventory_requisition_item where id = ?
                    """, line.itemId());
            long goodsId = ((Number) item.get("goods_id")).longValue();
            long colorId = ((Number) item.get("color_id")).longValue();
            long formatId = ((Number) item.get("format_id")).longValue();
            long warehouseId = ((Number) item.get("warehouse_id")).longValue();
            long selling = ((Number) item.get("selling")).longValue();
            StockService.Snapshot snapshot = new StockService.Snapshot(goodsId,
                    (String) item.get("goods_name"), colorId, text(item.get("color_name")),
                    formatId, text(item.get("format_name")));
            derived.add(new ManifestService.DerivedItem(snapshot, warehouseId, null, line.num(),
                    selling, null, null, line.itemId(), null, null));
            refundTotal += selling * line.num() / 1000;
            refundItems.add(new ConstructionAccessoryApi.Item(goodsId,
                    (String) item.get("goods_name"), line.num(), selling * line.num() / 1000));
        }
        manifests.createDerived(orgId, ManifestService.TYPE_AR,
                ((Number) row.get("order_id")).longValue(), ManifestService.SOURCE_REQUISITION,
                requisitionId, derived, operatorId);
        if (type == TYPE_COMMON && refundTotal > 0) {
            accessoryQuotas.refund(new ConstructionAccessoryApi.RefundCmd(orgId,
                    ((Number) row.get("order_id")).longValue(), requisitionId,
                    (String) row.get("code"), refundItems, refundTotal, operatorId));
        }
        return requisitionId;
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Long orderId, Integer status, Integer type,
            int pageNo, int size) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (orderId != null) {
            where.append(" and order_id = ?");
            args.add(orderId);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject("select count(*) from inventory_requisition" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((Math.max(pageNo, 1) - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, house_label, type, status, total, comment,
                       created_by_name, outbound_at, arrive_at, created_at
                from inventory_requisition""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long requisitionId) {
        Map<String, Object> row = new LinkedHashMap<>(loadDetail(orgId, requisitionId));
        row.put("items", jdbc.queryForList("""
                select id, goods_id, goods_name, color_name, format_name, warehouse_id, warehouse_name,
                       num, selling, total, outbound_num, returned_num, sort
                from inventory_requisition_item where requisition_id = ? order by sort
                """, requisitionId));
        row.put("manifests", jdbc.queryForList("""
                select id, code, type, status, confirmed_at, created_at
                from inventory_manifest where org_id = ? and source_type = ? and source_id = ?
                order by id desc
                """, orgId, ManifestService.SOURCE_REQUISITION, requisitionId));
        return row;
    }

    // ---- 内部 ----

    private record DerivedRow(StockService.Snapshot snapshot, long warehouseId, long num,
                              long selling, long lineTotal) {
    }

    private List<ConstructionAccessoryApi.Item> toContractItems(List<DerivedRow> rows) {
        List<ConstructionAccessoryApi.Item> items = new ArrayList<>();
        for (DerivedRow row : rows) {
            items.add(new ConstructionAccessoryApi.Item(row.snapshot().goodsId(),
                    row.snapshot().goodsName(), row.num(), row.lineTotal()));
        }
        return items;
    }

    private List<ConstructionAccessoryApi.Item> itemsFromDb(long requisitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select goods_id, goods_name, num, selling from inventory_requisition_item
                where requisition_id = ? order by sort
                """, requisitionId);
        List<ConstructionAccessoryApi.Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long num = ((Number) row.get("num")).longValue();
            long selling = ((Number) row.get("selling")).longValue();
            items.add(new ConstructionAccessoryApi.Item(((Number) row.get("goods_id")).longValue(),
                    (String) row.get("goods_name"), num, num * selling / 1000));
        }
        return items;
    }

    private String warehouseName(long orgId, long warehouseId) {
        return jdbc.queryForObject(
                "select name from inventory_warehouse where id = ? and org_id = ?",
                String.class, warehouseId, orgId);
    }

    private String nextCode(long orgId) {
        int dateKey = Integer.parseInt(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
        for (int attempt = 0; attempt < 3; attempt++) {
            int updated = jdbc.update(
                    "update inventory_counter set last_seq = last_insert_id(last_seq + 1) "
                            + "where org_id = ? and prefix = ? and date_key = ?",
                    orgId, "RQ", dateKey);
            if (updated > 0) {
                long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
                return String.format("RQ%s%04d", dateKey, seq);
            }
            try {
                jdbc.update(
                        "insert into inventory_counter(org_id, prefix, date_key, last_seq) values (?,?,?,1)",
                        orgId, "RQ", dateKey);
                return String.format("RQ%s%04d", dateKey, 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首插撞唯一键,重试自增
            }
        }
        throw new BizException(CODE_BAD_STATE, msg("inventory.state.illegal"));
    }

    private Map<String, Object> load(long orgId, long requisitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, type, status, total
                from inventory_requisition where id = ? and org_id = ?
                """, requisitionId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.notfound");
        return rows.get(0);
    }

    private Map<String, Object> loadDetail(long orgId, long requisitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, house_label, type, status, total, comment,
                       fetch_time, created_by_name, outbound_at, arrive_at, close_at, created_at
                from inventory_requisition where id = ? and org_id = ?
                """, requisitionId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.notfound");
        return rows.get(0);
    }

    private java.time.LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException e) {
            throw new BizException(CODE_PARAM, msg("inventory.param.invalid"));
        }
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
