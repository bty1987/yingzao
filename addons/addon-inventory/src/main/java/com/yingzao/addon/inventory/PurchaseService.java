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

import com.yingzao.addons.api.SupplierApi;
import com.yingzao.addons.api.event.StockPurchaseChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 采购单:入库轴 0初始→(分批 PU 入库按 Σsurplus 判定)→1部分/2全部,+3 已取消(营造新增终态)。
 * 入库 = 采购明细 surplus 原子条件扣减(修老 check-then-act)+ PU 货单派生 + 实时库存入库;
 * 取消/删项释放采购余量走 StockPurchaseChangedEvent 事件(修老 punum 永不释放,销售域
 * 订阅回写自己的账,本域零直写 sales 表);老结算轴 balanceStatus 不迁——采购结算统一
 * addon-payment(purchasebalance 生产 0 行,不复刻手工台账);balanceTime/deliveryTime
 * 死列不迁;260028 恒假守卫不迁(本域以原子守卫替代)。
 */
@Service
public class PurchaseService {

    public static final int CODE_NOT_FOUND = 310060;
    public static final int CODE_INBOUND_OVER = 310061;
    public static final int CODE_ALL_INBOUND = 310062;
    public static final int CODE_CANCELLED = 310063;
    public static final int CODE_EDIT_FROZEN = 310064;
    /** 厂商引用化(V1203,外辅 310058/310059 同构两态):缺失与停用是不同运营动作。 */
    public static final int CODE_SUPPLIER_NOT_FOUND = 310065;
    public static final int CODE_SUPPLIER_DISABLED = 310066;
    public static final int CODE_BAD_STATE = 310002;
    public static final int CODE_PARAM = 310003;

    public static final int ST_INIT = 0;
    public static final int ST_PARTIAL = 1;
    public static final int ST_ALL_INBOUND = 2;
    public static final int ST_CANCELLED = 3;

    public record ItemCmd(Long goodsId, Long colorId, Long formatId, Long num, Long inprice,
                          Long salesItemId) {
    }

    public record InboundLineCmd(Long itemId, Long num) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final StockService stocks;
    private final ManifestService manifests;
    private final YzEventBus eventBus;
    private final SupplierApi suppliers;

    public PurchaseService(JdbcTemplate jdbc, MessageSource messages, StockService stocks,
            ManifestService manifests, YzEventBus eventBus, SupplierApi suppliers) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.stocks = stocks;
        this.manifests = manifests;
        this.eventBus = eventBus;
        this.suppliers = suppliers;
    }

    @Transactional
    public long create(long orgId, Long salesId, Long supplierId, List<ItemCmd> items,
            String remark, long operatorId) {
        require(items != null && !items.isEmpty(), ManifestService.CODE_EMPTY_ITEMS,
                "inventory.items.empty");
        // V1203 厂商引用化(外辅 V1202 同款):必传 supplierId,存在且启用才可挂单,
        // supplier_name 收窄为官方名称快照(禁前端传名);存量自由登记行 NULL 只读保留。
        require(supplierId != null && supplierId > 0, CODE_PARAM, "inventory.param.invalid");
        SupplierApi.SupplierSummary supplier = suppliers.querySupplier(orgId, supplierId)
                .orElseThrow(() -> new BizException(CODE_SUPPLIER_NOT_FOUND,
                        msg("inventory.purchase.supplierNotFound")));
        require(supplier.status() == 0, CODE_SUPPLIER_DISABLED,
                "inventory.purchase.supplierDisabled");
        jdbc.update("""
                insert into inventory_purchase(org_id, code, sales_id, status, supplier_id,
                    supplier_name, remark, created_by, created_by_name)
                values (?,?,?,?,?,?,?,?,?)
                """, orgId, nextCode(orgId), salesId, ST_INIT, supplierId, supplier.name(),
                blankToNull(remark), operatorId, displayName(operatorId));
        long purchaseId = jdbc.queryForObject("select last_insert_id()", Long.class);
        int sort = 0;
        for (ItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null && cmd.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(cmd.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            require(cmd.inprice() == null || cmd.inprice() >= 0, CODE_PARAM, "inventory.param.invalid");
            StockService.Snapshot snapshot = stocks.snapshot(orgId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()));
            sort++;
            jdbc.update("""
                    insert into inventory_purchase_item(org_id, purchase_id, goods_id, color_id,
                        format_id, goods_name, color_name, format_name, num, inprice, surplus,
                        sales_item_id, sort)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, purchaseId, snapshot.goodsId(), snapshot.colorId(),
                    snapshot.formatId(), snapshot.goodsName(), snapshot.colorName(),
                    snapshot.formatName(), cmd.num(), nvl(cmd.inprice()), cmd.num(),
                    cmd.salesItemId(), sort);
        }
        return purchaseId;
    }

    /** 分批入库:status∈{0,1};明细 surplus 原子扣减 + PU 货单派生(整批一仓)+ 库存入库;
     *  Σsurplus==0 → 2 全部入库,否则 → 1 部分。 */
    @Transactional
    public long inbound(long orgId, long purchaseId, Long warehouseId, List<InboundLineCmd> lines,
            long operatorId) {
        Map<String, Object> purchase = load(orgId, purchaseId);
        int status = ((Number) purchase.get("status")).intValue();
        require(status == ST_INIT || status == ST_PARTIAL,
                status == ST_ALL_INBOUND ? CODE_ALL_INBOUND : CODE_CANCELLED,
                status == ST_ALL_INBOUND ? "inventory.purchase.allInbound"
                        : "inventory.purchase.cancelled");
        require(warehouseId != null && lines != null && !lines.isEmpty(), CODE_PARAM,
                "inventory.param.invalid");
        stocks.requireWarehouse(orgId, warehouseId);
        List<ManifestService.DerivedItem> derived = new ArrayList<>();
        for (InboundLineCmd line : lines) {
            require(line != null && line.itemId() != null && line.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(line.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            int updated = jdbc.update("""
                    update inventory_purchase_item set surplus = surplus - ?
                    where id = ? and org_id = ? and purchase_id = ? and surplus >= ?
                    """, line.num(), line.itemId(), orgId, purchaseId, line.num());
            require(updated > 0, CODE_INBOUND_OVER, "inventory.purchase.inboundOver");
            Map<String, Object> item = jdbc.queryForMap("""
                    select goods_id, color_id, format_id, goods_name, color_name, format_name,
                           inprice, sales_item_id
                    from inventory_purchase_item where id = ?
                    """, line.itemId());
            long goodsId = ((Number) item.get("goods_id")).longValue();
            long colorId = ((Number) item.get("color_id")).longValue();
            long formatId = ((Number) item.get("format_id")).longValue();
            long inprice = ((Number) item.get("inprice")).longValue();
            StockService.Snapshot snapshot = new StockService.Snapshot(goodsId,
                    (String) item.get("goods_name"), colorId, text(item.get("color_name")),
                    formatId, text(item.get("format_name")));
            derived.add(new ManifestService.DerivedItem(snapshot, warehouseId, null, line.num(),
                    inprice, null, item.get("sales_item_id") == null ? null
                            : ((Number) item.get("sales_item_id")).longValue(),
                    null, line.itemId(), null));
        }
        long manifestId = manifests.createDerived(orgId, ManifestService.TYPE_PU,
                null, ManifestService.SOURCE_PURCHASE, purchaseId, derived, operatorId);
        boolean allIn = remaining(orgId, purchaseId) == 0;
        jdbc.update("""
                update inventory_purchase set status = ?, inbound_at = case when ? = 2 then now()
                    else inbound_at end where id = ?
                """, allIn ? ST_ALL_INBOUND : ST_PARTIAL, allIn ? 2 : 1, purchaseId);
        return manifestId;
    }

    /** 删项(仅初始态):释放余量走事件(修老 punum 泄漏的采购侧对照)。 */
    @Transactional
    public void removeItem(long orgId, long purchaseId, long itemId, long operatorId) {
        Map<String, Object> purchase = load(orgId, purchaseId);
        require(((Number) purchase.get("status")).intValue() == ST_INIT, CODE_EDIT_FROZEN,
                "inventory.purchase.editFrozen");
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, goods_id, num, inprice, surplus, sales_item_id
                from inventory_purchase_item where id = ? and org_id = ? and purchase_id = ?
                """, itemId, orgId, purchaseId);
        require(!items.isEmpty(), CODE_NOT_FOUND, "inventory.purchase.itemNotFound");
        Map<String, Object> item = items.get(0);
        jdbc.update("delete from inventory_purchase_item where id = ?", itemId);
        publishRelease(orgId, purchaseId, StockPurchaseChangedEvent.ACTION_ITEM_REMOVED,
                List.of(item), operatorId);
    }

    /** 取消:{0,1}→3,全部余量释放走事件(修老取消永久烧掉销售项可采购余量)。 */
    @Transactional
    public void cancel(long orgId, long purchaseId, long operatorId) {
        Map<String, Object> purchase = load(orgId, purchaseId);
        int status = ((Number) purchase.get("status")).intValue();
        require(status == ST_INIT || status == ST_PARTIAL,
                status == ST_ALL_INBOUND ? CODE_ALL_INBOUND : CODE_CANCELLED,
                status == ST_ALL_INBOUND ? "inventory.purchase.allInbound"
                        : "inventory.purchase.cancelled");
        jdbc.update("""
                update inventory_purchase set status = ?, cancelled_at = now() where id = ?
                """, ST_CANCELLED, purchaseId);
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, goods_id, num, inprice, surplus, sales_item_id
                from inventory_purchase_item where purchase_id = ?
                """, purchaseId);
        publishRelease(orgId, purchaseId, StockPurchaseChangedEvent.ACTION_CANCELLED, items, operatorId);
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Integer status, String code, int pageNo, int size) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (notBlank(code)) {
            where.append(" and code like ?");
            args.add("%" + code.trim() + "%");
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject("select count(*) from inventory_purchase" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((Math.max(pageNo, 1) - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, sales_id, status, supplier_id, supplier_name, remark, created_by_name,
                       inbound_at, cancelled_at, created_at
                from inventory_purchase""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long purchaseId) {
        Map<String, Object> row = new LinkedHashMap<>(loadDetail(orgId, purchaseId));
        row.put("items", jdbc.queryForList("""
                select id, goods_id, goods_name, color_name, format_name, num, inprice, surplus,
                       sales_item_id, sort
                from inventory_purchase_item where purchase_id = ? order by sort
                """, purchaseId));
        row.put("manifests", jdbc.queryForList("""
                select id, code, type, status, confirmed_at, created_at
                from inventory_manifest where org_id = ? and source_type = ? and source_id = ?
                order by id desc
                """, orgId, ManifestService.SOURCE_PURCHASE, purchaseId));
        return row;
    }

    // ---- 内部 ----

    private void publishRelease(long orgId, long purchaseId, String action,
            List<Map<String, Object>> items, long operatorId) {
        List<StockPurchaseChangedEvent.Item> release = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long surplus = ((Number) item.get("surplus")).longValue();
            release.add(new StockPurchaseChangedEvent.Item(
                    ((Number) item.get("id")).longValue(),
                    item.get("sales_item_id") == null ? null
                            : ((Number) item.get("sales_item_id")).longValue(),
                    ((Number) item.get("goods_id")).longValue(), surplus,
                    ((Number) item.get("inprice")).longValue()));
        }
        eventBus.publish(new StockPurchaseChangedEvent(purchaseId, orgId, action, release, operatorId),
                "purchase:" + purchaseId + ":" + action);
    }

    private long remaining(long orgId, long purchaseId) {
        Long remaining = jdbc.queryForObject("""
                select coalesce(sum(surplus), 0) from inventory_purchase_item where purchase_id = ?
                """, Long.class, purchaseId);
        return remaining == null ? 0 : remaining;
    }

    private String nextCode(long orgId) {
        int dateKey = Integer.parseInt(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
        for (int attempt = 0; attempt < 3; attempt++) {
            int updated = jdbc.update(
                    "update inventory_counter set last_seq = last_insert_id(last_seq + 1) "
                            + "where org_id = ? and prefix = ? and date_key = ?",
                    orgId, "PO", dateKey);
            if (updated > 0) {
                long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
                return String.format("PO%s%04d", dateKey, seq);
            }
            try {
                jdbc.update(
                        "insert into inventory_counter(org_id, prefix, date_key, last_seq) values (?,?,?,1)",
                        orgId, "PO", dateKey);
                return String.format("PO%s%04d", dateKey, 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首插撞唯一键,重试自增
            }
        }
        throw new BizException(CODE_BAD_STATE, msg("inventory.state.illegal"));
    }

    private Map<String, Object> load(long orgId, long purchaseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, sales_id, status from inventory_purchase where id = ? and org_id = ?
                """, purchaseId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.purchase.notFound");
        return rows.get(0);
    }

    private Map<String, Object> loadDetail(long orgId, long purchaseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, sales_id, status, supplier_id, supplier_name, remark, created_by_name,
                       inbound_at, cancelled_at, created_at
                from inventory_purchase where id = ? and org_id = ?
                """, purchaseId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.purchase.notFound");
        return rows.get(0);
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

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
