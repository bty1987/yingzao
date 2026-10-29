package com.yingzao.addon.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.BrandApi;
import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.SupplierApi;
import com.yingzao.addons.api.event.SettlementItemReadyEvent;
import com.yingzao.addons.api.event.SettlementItemWithdrawnEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 外销子域(品牌厂商外销单,docs/prd/addon-sales-oales.md 按推荐拍板;300100 段)。
 *
 * 状态机(老 7 态收敛 6 态):0 草稿 →1 待确认 →2 已确认(confirm 建款线)→3 已收款
 * (PaymentCollectedEvent 回程)→4 已完结;1 →reject/cancel→0(驳回回草稿+原因留痕,
 * 驳回态消亡);仅 0/1/2 →8 已废弃(3/4 拒——修老「任意态可废不回滚已收款」)。
 * 退货单独立单头(type=1,foid=原单)跳态出生=2(明细默认复制可改),不走提交确认;
 * 其「收款」语义=原单款项退款通用链,本域无款线,finish 守卫=2(自主决策:PRD 未列退货
 * 完结路径,退货无应收款线,确认即可完结)。
 *
 * 营造修正(RPD 老缺陷收敛表):明细 oid 修老恒空断链;total/profit 全厘服务端算或守卫
 * (0≤profit≤total;成本列不建利润直接展示——开放问题 3:老明细自由文本无成本数据源,
 * 「服务端按明细算(total−成本)」不可实现,落为手填+服务端守卫);聚合/结算按 type 分支
 * 符号(购货正计/退货负计,对齐老 balanceoales if(type=0,profit,−profit))。
 * 结算接入零新基建:status→2 发 SettlementItemReadyEvent(sales:oales,income=±total、
 * payment=±(total−profit)——balance 桶 profit=income−payment=±profit,amount 应付厂商);
 * abate(自 2)发 SettlementItemWithdrawnEvent + cancelBill 未收款项单。
 */
@Service
public class OalesService {

    public static final int ST_DRAFT = 0;
    public static final int ST_SUBMITTED = 1;
    public static final int ST_CONFIRMED = 2;
    public static final int ST_COLLECTED = 3;
    public static final int ST_FINISHED = 4;
    public static final int ST_ABATED = 8;

    public static final int TYPE_PURCHASE = 0;
    public static final int TYPE_REFUND = 1;

    public static final int CODE_NOT_FOUND = 300101;
    public static final int CODE_STATE = 300102;
    public static final int CODE_ITEM = 300103;
    public static final int CODE_PROFIT = 300104;
    public static final int CODE_BRAND = 300105;
    public static final int CODE_NO_SUPPLIER = 300106;
    public static final int CODE_ORDER = 300107;
    public static final int CODE_REFUND_SOURCE = 300108;
    public static final int CODE_ABATE = 300109;
    public static final int CODE_FINISH = 300110;
    public static final int CODE_CANCEL = 300111;

    /** 款项/结算协议键:biz 三元组 (sales, oales, 单头 id);结算桶 settlement_type=sales:oales。 */
    public static final String BIZ_ADDON = "sales";
    public static final String BIZ_TYPE = "oales";
    public static final String TYPE_CODE = "sales_oales";
    public static final String SOURCE_TYPE_OALES = "oales";

    public record ItemCmd(String name, String format, String color, String model, Long price, Long num) {
    }

    /** 千分位精确乘算(行 total=price×num÷1000 HALF_UP,对齐 A5/NormService 先例)。 */
    static long milliMul(long a, long b) {
        return java.math.BigDecimal.valueOf(a).multiply(java.math.BigDecimal.valueOf(b))
                .divide(java.math.BigDecimal.valueOf(1000), 0, java.math.RoundingMode.HALF_UP)
                .longValue();
    }

    public record OalesCmd(Long orderId, Long brandId, Integer type, Long foid, Long profit,
            List<ItemCmd> items, String remark) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final PaymentApi payments;
    private final DecorationApi decorations;
    private final BrandApi brands;
    private final SupplierApi suppliers;

    public OalesService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            PaymentApi payments, DecorationApi decorations, BrandApi brands, SupplierApi suppliers) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.payments = payments;
        this.decorations = decorations;
        this.brands = brands;
        this.suppliers = suppliers;
    }

    // ---- 创建(购货=草稿;退货=跳态出生 2+结算事件) ----

    @Transactional
    public long create(long orgId, OalesCmd cmd, long operatorId) {
        require(cmd != null && cmd.orderId() != null, CODE_ORDER, "sales.oales.orderInvalid");
        int type = cmd.type() == null ? TYPE_PURCHASE : cmd.type();
        require(type == TYPE_PURCHASE || type == TYPE_REFUND, CODE_STATE, "sales.oales.state");

        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, cmd.orderId()).orElse(null);
        require(order != null && order.status() != 5, CODE_ORDER, "sales.oales.orderInvalid");

        long brandId;
        long sourceId = 0;
        List<ItemCmd> items = cmd.items();
        if (type == TYPE_REFUND) {
            require(cmd.foid() != null, CODE_REFUND_SOURCE, "sales.oales.refundSource");
            Map<String, Object> source = load(orgId, cmd.foid());
            int srcStatus = ((Number) source.get("status")).intValue();
            require(srcStatus == ST_CONFIRMED || srcStatus == ST_COLLECTED, CODE_REFUND_SOURCE,
                    "sales.oales.refundSource");
            brandId = ((Number) source.get("brand_id")).longValue();
            sourceId = cmd.foid();
            if (items == null || items.isEmpty()) { // 明细默认复制原单(可改)
                items = jdbc.query("""
                        select name, format, color, model, price, num from sales_oales_item
                        where oid = ? order by sort""", (rs, n) -> new ItemCmd(rs.getString("name"),
                        rs.getString("format"), rs.getString("color"), rs.getString("model"),
                        rs.getLong("price"), rs.getLong("num")), cmd.foid());
            }
        } else {
            require(cmd.brandId() != null, CODE_BRAND, "sales.oales.brand");
            brandId = cmd.brandId();
        }

        BrandApi.BrandSummary brand = brands.queryBrand(brandId).orElse(null);
        require(brand != null && brand.status() == 0, CODE_BRAND, "sales.oales.brand");
        List<SupplierApi.SupplierSummary> brandSuppliers = suppliers.queryBrandSuppliers(orgId, brandId);
        require(!brandSuppliers.isEmpty(), CODE_NO_SUPPLIER, "sales.oales.noSupplier");
        SupplierApi.SupplierSummary supplier = brandSuppliers.get(0); // 首家=主厂商(is_primary desc)

        List<ItemCmd> safeItems = items == null ? List.of() : items;
        require(!safeItems.isEmpty(), CODE_ITEM, "sales.oales.item");
        long total = 0;
        int sort = 1;
        List<Object[]> itemRows = new ArrayList<>();
        for (ItemCmd item : safeItems) {
            require(item != null && SalesOrderService.notBlank(item.name())
                    && item.name().trim().length() <= 128, CODE_ITEM, "sales.oales.item");
            require(item.price() != null && item.price() > 0 && item.num() != null && item.num() > 0,
                    CODE_ITEM, "sales.oales.item");
            long rowTotal = milliMul(item.price(), item.num());
            total += rowTotal;
            itemRows.add(new Object[] { item.name().trim(), SalesOrderService.blankToNull(item.format()),
                    SalesOrderService.blankToNull(item.color()), SalesOrderService.blankToNull(item.model()),
                    item.price(), item.num(), rowTotal, sort++ });
        }
        long profit = cmd.profit() == null ? 0 : cmd.profit();
        require(profit >= 0 && profit <= total, CODE_PROFIT, "sales.oales.profit");

        String no = nextNo(orgId, type == TYPE_REFUND ? "WR" : "WX");
        int born = type == TYPE_REFUND ? ST_CONFIRMED : ST_DRAFT;
        jdbc.update("""
                insert into sales_oales(org_id, order_id, no, foid, brand_id, brand_name, supplier_id,
                    supplier_name, type, total, profit, status, creater)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                orgId, cmd.orderId(), no, sourceId == 0 ? null : sourceId, brandId, brand.name(),
                supplier.supplierId(), supplier.name(), type, total, profit, born, operatorId);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        for (Object[] row : itemRows) {
            jdbc.update("""
                    insert into sales_oales_item(org_id, oid, name, format, color, model, price, num, total, sort)
                    values (?,?,?,?,?,?,?,?,?,?)""", orgId, id, row[0], row[1], row[2], row[3], row[4],
                    row[5], row[6], row[7]);
        }
        track(orgId, id, type == TYPE_REFUND ? "refund" : "create", 0, born,
                SalesOrderService.blankToNull(cmd.remark()), operatorId);
        if (type == TYPE_REFUND) { // 跳态出生即结算就绪(负利润进聚合)
            publishReady(orgId, load(orgId, id));
        }
        return id;
    }

    /** 草稿改单(全量替换明细重算;仅 0)。 */
    @Transactional
    public void update(long orgId, long id, OalesCmd cmd, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_DRAFT);
        require(cmd != null, CODE_ITEM, "sales.oales.item");
        long total = 0;
        int sort = 1;
        List<Object[]> itemRows = new ArrayList<>();
        for (ItemCmd item : cmd.items() == null ? List.<ItemCmd>of() : cmd.items()) {
            require(item != null && SalesOrderService.notBlank(item.name()), CODE_ITEM, "sales.oales.item");
            require(item.price() != null && item.price() > 0 && item.num() != null && item.num() > 0,
                    CODE_ITEM, "sales.oales.item");
            long rowTotal = milliMul(item.price(), item.num());
            total += rowTotal;
            itemRows.add(new Object[] { item.name().trim(), SalesOrderService.blankToNull(item.format()),
                    SalesOrderService.blankToNull(item.color()), SalesOrderService.blankToNull(item.model()),
                    item.price(), item.num(), rowTotal, sort++ });
        }
        require(!itemRows.isEmpty(), CODE_ITEM, "sales.oales.item");
        long profit = cmd.profit() == null ? 0 : cmd.profit();
        require(profit >= 0 && profit <= total, CODE_PROFIT, "sales.oales.profit");
        jdbc.update("delete from sales_oales_item where oid = ?", id);
        for (Object[] item : itemRows) {
            jdbc.update("""
                    insert into sales_oales_item(org_id, oid, name, format, color, model, price, num, total, sort)
                    values (?,?,?,?,?,?,?,?,?,?)""", orgId, id, item[0], item[1], item[2], item[3], item[4],
                    item[5], item[6], item[7]);
        }
        jdbc.update("update sales_oales set total = ?, profit = ? where id = ?", total, profit, id);
        track(orgId, id, "update", ST_DRAFT, ST_DRAFT, null, operatorId);
    }

    /** 草稿物理删(单+明细+留痕同事务全清,零孤儿——修老先删单再补记录)。 */
    @Transactional
    public void deleteDraft(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_DRAFT);
        jdbc.update("delete from sales_oales_item where oid = ?", id);
        jdbc.update("delete from sales_oales_track where oid = ?", id);
        jdbc.update("delete from sales_oales where id = ?", id);
    }

    // ---- 状态机 ----

    @Transactional
    public void submit(long orgId, long id, long operatorId) {
        transition(orgId, id, ST_DRAFT, ST_SUBMITTED, "submit", null, operatorId);
    }

    /** 撤回:仅待确认 1 → 0(300111)。 */
    @Transactional
    public void cancel(long orgId, long id, long operatorId) {
        transition(orgId, id, ST_SUBMITTED, ST_DRAFT, "cancel", null, operatorId);
    }

    /** 驳回:1 → 0 + 原因留痕(驳回态消亡,改后重提)。 */
    @Transactional
    public void reject(long orgId, long id, String reason, long operatorId) {
        require(SalesOrderService.notBlank(reason), CODE_STATE, "sales.oales.state");
        transition(orgId, id, ST_SUBMITTED, ST_DRAFT, "reject", reason.trim(), operatorId);
    }

    /**
     * 确认:1 → 2;翻转老「先款后确认」=此时建款项单(typeCode=sales_oales 懒预置,
     * 对齐 ar_diff 惯例);并发 SettlementItemReadyEvent(结算就绪唯一入口)。
     */
    @Transactional
    public void confirm(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_SUBMITTED);
        Integer typeExists = jdbc.queryForObject(
                "select count(*) from payment_bill_type where org_id = ? and code = ?",
                Integer.class, orgId, TYPE_CODE);
        if (typeExists == null || typeExists == 0) {
            jdbc.update("insert into payment_bill_type(org_id, code, name, sort) values (?,?,?,91)",
                    orgId, TYPE_CODE, "外销货款");
        }
        long dpId = payments.createBill(new PaymentApi.CreateBillCmd(orgId, BIZ_ADDON, BIZ_TYPE,
                String.valueOf(id), TYPE_CODE, ((Number) row.get("total")).longValue(),
                "外销货款 " + row.get("no")));
        int updated = jdbc.update("""
                update sales_oales set status = ?, dp_id = ?, confirm_time = now()
                where id = ? and org_id = ? and status = ?""",
                ST_CONFIRMED, dpId, id, orgId, ST_SUBMITTED);
        require(updated == 1, CODE_STATE, "sales.oales.state");
        track(orgId, id, "confirm", ST_SUBMITTED, ST_CONFIRMED, null, operatorId);
        publishReady(orgId, load(orgId, id));
    }

    /** 收款回程(PaymentCollectedEvent typeCode=sales_oales):2 → 3 条件 UPDATE 幂等。 */
    @Transactional
    public void onPaymentCollected(long orgId, long oalesId) {
        jdbc.update("""
                update sales_oales set status = ?
                where id = ? and org_id = ? and status = ?""",
                ST_COLLECTED, oalesId, orgId, ST_CONFIRMED);
    }

    /** 完结:购货 3→4(须收齐款);退货 2→4(无款线,确认即可完结)。 */
    @Transactional
    public void finish(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        int from = ((Number) row.get("type")).intValue() == TYPE_REFUND ? ST_CONFIRMED : ST_COLLECTED;
        if (((Number) row.get("status")).intValue() != from) {
            throw new BizException(CODE_FINISH, msg("sales.oales.finish"));
        }
        int updated = jdbc.update("""
                update sales_oales set status = ?, finish_time = now()
                where id = ? and org_id = ? and status = ?""",
                ST_FINISHED, id, orgId, from);
        require(updated == 1, CODE_STATE, "sales.oales.state");
        track(orgId, id, "finish", from, ST_FINISHED, null, operatorId);
    }

    /** 废弃:仅 0/1/2 → 8(3/4 拒 300109);自 2 废=撤结算事件+作废未收款项单(同事务)。 */
    @Transactional
    public void abate(long orgId, long id, String reason, long operatorId) {
        require(SalesOrderService.notBlank(reason) && reason.trim().length() <= 200, CODE_STATE,
                "sales.oales.state");
        Map<String, Object> row = load(orgId, id);
        int from = ((Number) row.get("status")).intValue();
        require(from == ST_DRAFT || from == ST_SUBMITTED || from == ST_CONFIRMED, CODE_ABATE,
                "sales.oales.abate");
        int updated = jdbc.update("""
                update sales_oales set status = ?, abate_reason = ?
                where id = ? and org_id = ? and status = ?""",
                ST_ABATED, reason.trim(), id, orgId, from);
        require(updated == 1, CODE_STATE, "sales.oales.state");
        if (from == ST_CONFIRMED) {
            Object dpId = row.get("dp_id");
            if (dpId != null && ((Number) dpId).longValue() > 0) {
                payments.cancelBill(orgId, ((Number) dpId).longValue());
            }
            eventBus.publish(new SettlementItemWithdrawnEvent(orgId, BIZ_ADDON, SOURCE_TYPE_OALES,
                    String.valueOf(id), reason.trim()),
                    "settle-item:" + BIZ_ADDON + ":" + SOURCE_TYPE_OALES + ":" + id + ":withdrawn");
        }
        track(orgId, id, "abate", from, ST_ABATED, reason.trim(), operatorId);
    }

    // ---- 查询面(单头不冗余 house/customer,读取期经 DecorationApi 契约补展示) ----

    public Map<String, Object> page(long orgId, Long orderId, Long brandId, Integer type,
            Integer status, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (orderId != null) {
            where.append(" and order_id = ?");
            args.add(orderId);
        }
        if (brandId != null) {
            where.append(" and brand_id = ?");
            args.add(brandId);
        }
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from sales_oales" + where, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, no, foid, brand_id, brand_name, supplier_id, supplier_name, type,
                       total, profit, dp_id, status, abate_reason, creater, created_at, confirm_time, finish_time
                from sales_oales""" + where + " order by id desc limit ? offset ?",
                SalesOrderService.appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        for (Map<String, Object> row : rows) {
            decorateOrder(orgId, row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long id) {
        Map<String, Object> row = new LinkedHashMap<>(load(orgId, id));
        decorateOrder(orgId, row);
        row.put("items", jdbc.queryForList("""
                select id, name, format, color, model, price, num, total, sort
                from sales_oales_item where oid = ? order by sort""", id));
        row.put("tracks", jdbc.queryForList("""
                select action, before_status, after_status, detail, operator_name, created_at
                from sales_oales_track where oid = ? order by id desc limit 100""", id));
        row.put("bills", payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(id)));
        return row;
    }

    // ---- 内部 ----

    private void decorateOrder(long orgId, Map<String, Object> row) {
        decorations.queryOrder(orgId, ((Number) row.get("order_id")).longValue())
                .ifPresent(order -> {
                    row.put("order_no", order.orderNo());
                    row.put("customer_name", order.customerName());
                    row.put("house_label", order.houseLabel());
                });
    }

    /** 结算就绪事件:income=±total/payment=±(total−profit)——balance 桶 profit=差值、amount=应付厂商。 */
    private void publishReady(long orgId, Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        long total = ((Number) row.get("total")).longValue();
        long profit = ((Number) row.get("profit")).longValue();
        long sign = ((Number) row.get("type")).intValue() == TYPE_REFUND ? -1 : 1;
        eventBus.publish(new SettlementItemReadyEvent(orgId, BIZ_ADDON, SOURCE_TYPE_OALES,
                String.valueOf(id), ((Number) row.get("brand_id")).longValue(),
                String.valueOf(row.get("brand_name")),
                String.valueOf(row.get("supplier_id")), String.valueOf(row.get("supplier_name")),
                SalesSettlementService.GRADE_DEFAULT, 0L, 0L, sign * total, 0L,
                sign * (total - profit), "外销单 " + row.get("no") + " 利润结算"),
                "settle-item:" + BIZ_ADDON + ":" + SOURCE_TYPE_OALES + ":" + id + ":ready");
    }

    private void transition(long orgId, long id, int from, int to, String action, String detail,
            long operatorId) {
        load(orgId, id);
        int updated = jdbc.update(
                "update sales_oales set status = ? where id = ? and org_id = ? and status = ?",
                to, id, orgId, from);
        require(updated == 1, CODE_STATE, "sales.oales.state");
        track(orgId, id, action, from, to, detail, operatorId);
    }

    private void requireStatus(Map<String, Object> row, int expected) {
        if (((Number) row.get("status")).intValue() != expected) {
            throw new BizException(CODE_STATE, msg("sales.oales.state"));
        }
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_oales where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("sales.oales.notFound"));
        }
        return rows.get(0);
    }

    private void track(long orgId, long oid, String action, Integer from, Integer to, String detail,
            long operatorId) {
        jdbc.update("""
                insert into sales_oales_track(org_id, oid, action, before_status, after_status, detail,
                    operator_id, operator_name) values (?,?,?,?,?,?,?,?)""",
                orgId, oid, action, from, to, detail, operatorId, displayName(operatorId));
    }

    private String nextNo(long orgId, String prefix) {
        jdbc.update("""
                insert into sales_counter(org_id, prefix, date_key, last_seq)
                values (?,?,?,last_insert_id(1))
                on duplicate key update last_seq = last_insert_id(last_seq + 1)""",
                orgId, prefix, seqDate());
        long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
        return prefix + codeDate() + String.format("%04d", seq);
    }

    private String codeDate() {
        return java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    private int seqDate() {
        return Integer.parseInt(codeDate());
    }

    private String displayName(long userId) {
        List<String> names = jdbc.queryForList(
                "select display_name from yz_user where id = ?", String.class, userId);
        return names.isEmpty() ? String.valueOf(userId) : names.get(0);
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean condition, int code, String key) {
        if (!condition) {
            throw new BizException(code, msg(key));
        }
    }
}
