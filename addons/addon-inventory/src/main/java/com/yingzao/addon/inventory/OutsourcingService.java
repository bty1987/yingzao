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

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.SupplierApi;
import com.yingzao.core.web.BizException;

/**
 * 外辅采购单(老 occcessories 正名 outsourced purchase):向外部厂商定材辅料的物流+费用+结算。
 * 状态机 0初始→1已分配→2送货中→3已到货→4已结算→5已完成(补老字典缺 5 的不可达终态),
 * 作废 6 仅 0/1/2 可操作、单据保留结构性锁死(各写点显式校验),反结算 4→3;
 * 工程验收后锁定不可登记(projectStatus 经 DecorationApi 契约,修老 260089 直查工程表);
 * 金额全 long 服务端重算(修老 int×int 溢出 + total=0 生产事故口径);收费公式
 * =（price+超阶浮动)×num,费用编辑后服务端回写单头(修老全删全插不回写缺陷);
 * V1 金额单据内自闭环(生产即线下钱货),接 addon-payment 随 V2 评估;厂商挂单经
 * SupplierApi 契约校验(addon-supplier 就绪后的升级:废自由名称登记,supplier_id 引用
 * +官方名称快照,修老"任何 uid 都能被当厂商";存量自由登记行 supplier_id 为 NULL 只读保留)。
 */
@Service
public class OutsourcingService {

    public static final int CODE_NOT_FOUND = 310050;
    public static final int CODE_ACCEPTED_LOCKED = 310051;
    public static final int CODE_DELIVER_OVER = 310052;
    public static final int CODE_RECEIVE_OVER = 310053;
    public static final int CODE_CANCEL_TOO_LATE = 310054;
    public static final int CODE_CANCELLED = 310055;
    public static final int CODE_NOT_BALANCED = 310056;
    public static final int CODE_NOT_RECEIVED = 310057;
    public static final int CODE_SUPPLIER_NOT_FOUND = 310058;
    public static final int CODE_SUPPLIER_DISABLED = 310059;
    public static final int CODE_BAD_STATE = 310002;
    public static final int CODE_PARAM = 310003;

    public static final int ST_INIT = 0;
    public static final int ST_ASSIGNED = 1;
    public static final int ST_DELIVERING = 2;
    public static final int ST_RECEIVED = 3;
    public static final int ST_BALANCED = 4;
    public static final int ST_FINISHED = 5;
    public static final int ST_CANCELLED = 6;

    public record ItemCmd(Long goodsId, Long colorId, Long formatId, Long num, Long rbalance) {
    }

    public record QtyLineCmd(Long itemId, Long num) {
    }

    public record ChargeCmd(Long itemId, String name, Long price, Long surplus, Integer num) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final StockService stocks;
    private final DecorationApi decorations;
    private final com.yingzao.addons.api.GoodsApi goods;
    private final SupplierApi suppliers;

    public OutsourcingService(JdbcTemplate jdbc, MessageSource messages, StockService stocks,
            DecorationApi decorations, com.yingzao.addons.api.GoodsApi goods, SupplierApi suppliers) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.stocks = stocks;
        this.decorations = decorations;
        this.goods = goods;
        this.suppliers = suppliers;
    }

    @Transactional
    public long create(long orgId, Long orderId, List<ItemCmd> items, Integer isEnter,
            Integer isLift, Integer isExceed, Integer isAttic, Integer loadFloor, String remark,
            long operatorId) {
        require(orderId != null && items != null && !items.isEmpty(), CODE_PARAM,
                "inventory.param.invalid");
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_NOT_FOUND,
                        msg("inventory.outsourcing.decorationNotFound")));
        require(order.projectStatus() != 2, CODE_ACCEPTED_LOCKED, "inventory.outsourcing.accepted");
        long total = 0;
        long totalBalance = 0;
        List<OsItem> rows = new ArrayList<>();
        for (ItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null && cmd.num() != null && cmd.num() > 0,
                    CODE_PARAM, "inventory.param.invalid");
            StockService.Snapshot snapshot = stocks.snapshot(orgId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()));
            long selling = goodsSelling(orgId, cmd.goodsId());
            long rbalance = cmd.rbalance() == null ? selling : cmd.rbalance();
            require(rbalance >= 0, CODE_PARAM, "inventory.param.invalid");
            total += selling * cmd.num() / 1000;
            totalBalance += rbalance * cmd.num() / 1000;
            rows.add(new OsItem(snapshot, cmd.num(), rbalance));
        }
        jdbc.update("""
                insert into inventory_outsourcing(org_id, code, order_id, order_no, house_label,
                    status, is_enter, is_lift, is_exceed, is_attic, load_floor, total, rcharge,
                    charge, rbalance, vbalance, remark, created_by, created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, nextCode(orgId), orderId, order.orderNo(), order.houseLabel(), ST_INIT,
                flag(isEnter), flag(isLift), flag(isExceed), flag(isAttic),
                loadFloor == null ? 0 : loadFloor, total, 0L, 0L, totalBalance, totalBalance,
                blankToNull(remark), operatorId, displayName(operatorId));
        long outsourcingId = jdbc.queryForObject("select last_insert_id()", Long.class);
        int sort = 0;
        for (OsItem row : rows) {
            sort++;
            jdbc.update("""
                    insert into inventory_outsourcing_item(org_id, outsourcing_id, goods_id, color_id,
                        format_id, goods_name, color_name, format_name, num, rbalance, vbalance, sort)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, outsourcingId, row.snapshot().goodsId(), row.snapshot().colorId(),
                    row.snapshot().formatId(), row.snapshot().goodsName(), row.snapshot().colorName(),
                    row.snapshot().formatName(), row.num(), row.rbalance(), row.rbalance(), sort);
        }
        track(orgId, outsourcingId, "create", null, operatorId);
        return outsourcingId;
    }

    /**
     * 分配厂商:{0,1}→1。V1202 起厂商引用化:必传 supplierId,经 SupplierApi 校验存在且
     * 启用(不存在 310058/停用 310059,修老"任何名称都能挂单"),官方名称落 supplier_name
     * 快照(禁前端传入);supplier_phone 保留手输(厂商多联系方式,非主数据口径)。
     * 已分配(1)可再次调用=换厂商(重分配);存量自由登记单被重分配时补挂 supplier_id。
     */
    @Transactional
    public void assign(long orgId, long outsourcingId, Long supplierId, String supplierPhone,
            long operatorId) {
        require(supplierId != null && supplierId > 0, CODE_PARAM, "inventory.param.invalid");
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        int status = ((Number) row.get("status")).intValue();
        require(status == ST_INIT || status == ST_ASSIGNED, CODE_BAD_STATE, "inventory.state.illegal");
        SupplierApi.SupplierSummary supplier = suppliers.querySupplier(orgId, supplierId)
                .orElseThrow(() -> new BizException(CODE_SUPPLIER_NOT_FOUND,
                        msg("inventory.outsourcing.supplierNotFound")));
        require(supplier.status() == 0, CODE_SUPPLIER_DISABLED,
                "inventory.outsourcing.supplierDisabled");
        jdbc.update("""
                update inventory_outsourcing set status = ?, supplier_id = ?, supplier_name = ?,
                    supplier_phone = ?, assigned_at = coalesce(assigned_at, now())
                where id = ?
                """, ST_ASSIGNED, supplierId, supplier.name(), blankToNull(supplierPhone),
                outsourcingId);
        track(orgId, outsourcingId, "assign", supplierId + ":" + supplier.name(), operatorId);
    }

    /** 送货:1→2,逐项 sennum 原子累计(≤申请数量,修老无校验)。 */
    @Transactional
    public void deliver(long orgId, long outsourcingId, List<QtyLineCmd> lines, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        require(((Number) row.get("status")).intValue() == ST_ASSIGNED, CODE_BAD_STATE,
                "inventory.state.illegal");
        applyQty(orgId, outsourcingId, lines, "sennum", "num", CODE_DELIVER_OVER,
                "inventory.outsourcing.deliverOver");
        jdbc.update("""
                update inventory_outsourcing set status = ?, delivered_at = now() where id = ?
                """, ST_DELIVERING, outsourcingId);
        track(orgId, outsourcingId, "deliver", null, operatorId);
    }

    /** 到货:2→3,逐项 recnum 原子累计(≤送货数量)。 */
    @Transactional
    public void receive(long orgId, long outsourcingId, List<QtyLineCmd> lines, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        require(((Number) row.get("status")).intValue() == ST_DELIVERING, CODE_BAD_STATE,
                "inventory.state.illegal");
        applyQty(orgId, outsourcingId, lines, "recnum", "sennum", CODE_RECEIVE_OVER,
                "inventory.outsourcing.receiveOver");
        jdbc.update("""
                update inventory_outsourcing set status = ?, received_at = now() where id = ?
                """, ST_RECEIVED, outsourcingId);
        track(orgId, outsourcingId, "receive", null, operatorId);
    }

    /** 收费明细全量替换(仅结算前可编辑);公式 =(price+超阶浮动)×num 服务端重算,
     *  并回写 item.charge 与单头 charge/rcharge(修老全删全插不回写缺陷)。 */
    @Transactional
    public void saveCharges(long orgId, long outsourcingId, List<ChargeCmd> charges, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        int status = ((Number) row.get("status")).intValue();
        require(status < ST_BALANCED, CODE_BAD_STATE, "inventory.state.illegal");
        boolean isExceed = ((Number) row.get("is_exceed")).intValue() == 1;
        jdbc.update("delete from inventory_outsourcing_charge where outsourcing_id = ?", outsourcingId);
        Map<Long, Long> itemCharges = new LinkedHashMap<>();
        long totalCharge = 0;
        if (charges != null) {
            for (ChargeCmd cmd : charges) {
                require(cmd != null && notBlank(cmd.name()) && cmd.name().trim().length() <= 64
                        && cmd.num() != null && cmd.num() > 0, CODE_PARAM, "inventory.param.invalid");
                long price = nvl(cmd.price());
                long surplus = nvl(cmd.surplus());
                require(price >= 0 && surplus >= 0, CODE_PARAM, "inventory.param.invalid");
                long charge = (price + (isExceed ? surplus : 0)) * cmd.num();
                jdbc.update("""
                        insert into inventory_outsourcing_charge(org_id, outsourcing_id, item_id,
                            name, price, surplus, num, charge)
                        values (?,?,?,?,?,?,?,?)
                        """, orgId, outsourcingId, cmd.itemId(), cmd.name().trim(), price, surplus,
                        cmd.num(), charge);
                totalCharge += charge;
                if (cmd.itemId() != null) {
                    itemCharges.merge(cmd.itemId(), charge, Long::sum);
                }
            }
        }
        // 替换式编辑:明细行费用先清零再回填,口径自洽(修老全删全插不回写缺陷)
        jdbc.update("update inventory_outsourcing_item set charge = 0 where outsourcing_id = ?",
                outsourcingId);
        itemCharges.forEach((itemId, charge) -> jdbc.update(
                "update inventory_outsourcing_item set charge = ? where id = ? and outsourcing_id = ?",
                charge, itemId, outsourcingId));
        jdbc.update("update inventory_outsourcing set charge = ?, rcharge = ? where id = ?",
                totalCharge, totalCharge, outsourcingId);
        track(orgId, outsourcingId, "charge", "Σ=" + totalCharge, operatorId);
    }

    /** 结算:3→4(vbalance 确认为 rbalance 口径,修老人工手调不落痕;接 payment 随 V2)。 */
    @Transactional
    public void balance(long orgId, long outsourcingId, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        require(((Number) row.get("status")).intValue() == ST_RECEIVED, CODE_NOT_RECEIVED,
                "inventory.outsourcing.notReceived");
        jdbc.update("""
                update inventory_outsourcing set status = ?, balanced_at = now() where id = ?
                """, ST_BALANCED, outsourcingId);
        track(orgId, outsourcingId, "balance", null, operatorId);
    }

    /** 反结算:4→3,结算字段复位(vbalance 回 rbalance 口径,rcharge 回 charge)。 */
    @Transactional
    public void rejectBalance(long orgId, long outsourcingId, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        require(((Number) row.get("status")).intValue() == ST_BALANCED, CODE_NOT_BALANCED,
                "inventory.outsourcing.notBalanced");
        jdbc.update("""
                update inventory_outsourcing set status = ?, balanced_at = null,
                    vbalance = rbalance, rcharge = charge
                where id = ?
                """, ST_RECEIVED, outsourcingId);
        track(orgId, outsourcingId, "rejectBalance", null, operatorId);
    }

    /** 完成:4→5(补老字典缺 5 的终态,PRD 验收全流转)。 */
    @Transactional
    public void finish(long orgId, long outsourcingId, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        require(((Number) row.get("status")).intValue() == ST_BALANCED, CODE_BAD_STATE,
                "inventory.state.illegal");
        jdbc.update("""
                update inventory_outsourcing set status = ?, finished_at = now() where id = ?
                """, ST_FINISHED, outsourcingId);
        track(orgId, outsourcingId, "finish", null, operatorId);
    }

    /** 作废:仅 0/1/2 可操作(310054),单据保留结构性锁死(写点显式校验 310055)。 */
    @Transactional
    public void cancel(long orgId, long outsourcingId, String remark, long operatorId) {
        Map<String, Object> row = load(orgId, outsourcingId);
        requireNotCancelled(row);
        int status = ((Number) row.get("status")).intValue();
        require(status <= ST_DELIVERING, CODE_CANCEL_TOO_LATE, "inventory.outsourcing.cancelTooLate");
        jdbc.update("""
                update inventory_outsourcing set status = ?, cancelled_at = now() where id = ?
                """, ST_CANCELLED, outsourcingId);
        track(orgId, outsourcingId, "cancel", blankToNull(remark), operatorId);
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Long orderId, Integer status, String supplierName,
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
        if (notBlank(supplierName)) {
            where.append(" and supplier_name like ?");
            args.add("%" + supplierName.trim() + "%");
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject("select count(*) from inventory_outsourcing" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((Math.max(pageNo, 1) - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, house_label, status, supplier_name, total,
                       charge, rcharge, rbalance, vbalance, is_enter, is_lift, is_exceed, is_attic,
                       load_floor, created_by_name, created_at
                from inventory_outsourcing""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long outsourcingId) {
        Map<String, Object> row = new LinkedHashMap<>(loadDetail(orgId, outsourcingId));
        row.put("items", jdbc.queryForList("""
                select id, goods_id, goods_name, color_name, format_name, num, sennum, recnum,
                       rbalance, vbalance, charge, sort
                from inventory_outsourcing_item where outsourcing_id = ? order by sort
                """, outsourcingId));
        row.put("charges", jdbc.queryForList("""
                select id, item_id, name, price, surplus, num, charge
                from inventory_outsourcing_charge where outsourcing_id = ? order by id
                """, outsourcingId));
        row.put("tracks", jdbc.queryForList("""
                select action, detail, operator_name, created_at
                from inventory_outsourcing_track where outsourcing_id = ? order by id desc limit 100
                """, outsourcingId));
        return row;
    }

    // ---- 内部 ----

    private record OsItem(StockService.Snapshot snapshot, long num, long rbalance) {
    }

    private void applyQty(long orgId, long outsourcingId, List<QtyLineCmd> lines, String targetCol,
            String boundCol, int code, String msgKey) {
        require(lines != null && !lines.isEmpty(), CODE_PARAM, "inventory.param.invalid");
        for (QtyLineCmd line : lines) {
            require(line != null && line.itemId() != null && line.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(line.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            int updated = jdbc.update("""
                    update inventory_outsourcing_item set %s = %s + ?
                    where id = ? and org_id = ? and outsourcing_id = ? and %s + ? <= %s
                    """.formatted(targetCol, targetCol, targetCol, boundCol),
                    line.num(), line.itemId(), orgId, outsourcingId, line.num());
            require(updated > 0, code, msgKey);
        }
    }

    private void requireNotCancelled(Map<String, Object> row) {
        require(((Number) row.get("status")).intValue() != ST_CANCELLED, CODE_CANCELLED,
                "inventory.outsourcing.cancelled");
    }

    private long goodsSelling(long orgId, long goodsId) {
        return goods.queryGoods(orgId, goodsId)
                .orElseThrow(() -> new BizException(StockService.CODE_GOODS_NOT_FOUND,
                        msg("inventory.goods.notFound")))
                .selling();
    }

    private void track(long orgId, long outsourcingId, String action, String detail, long operatorId) {
        jdbc.update("""
                insert into inventory_outsourcing_track(org_id, outsourcing_id, action, detail,
                    operator_id, operator_name)
                values (?,?,?,?,?,?)
                """, orgId, outsourcingId, action, blankToNull(detail), operatorId,
                displayName(operatorId));
    }

    private String nextCode(long orgId) {
        int dateKey = Integer.parseInt(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
        for (int attempt = 0; attempt < 3; attempt++) {
            int updated = jdbc.update(
                    "update inventory_counter set last_seq = last_insert_id(last_seq + 1) "
                            + "where org_id = ? and prefix = ? and date_key = ?",
                    orgId, "OS", dateKey);
            if (updated > 0) {
                long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
                return String.format("OS%s%04d", dateKey, seq);
            }
            try {
                jdbc.update(
                        "insert into inventory_counter(org_id, prefix, date_key, last_seq) values (?,?,?,1)",
                        orgId, "OS", dateKey);
                return String.format("OS%s%04d", dateKey, 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首插撞唯一键,重试自增
            }
        }
        throw new BizException(CODE_BAD_STATE, msg("inventory.state.illegal"));
    }

    private Map<String, Object> load(long orgId, long outsourcingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, status, is_exceed
                from inventory_outsourcing where id = ? and org_id = ?
                """, outsourcingId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.outsourcing.notFound");
        return rows.get(0);
    }

    private Map<String, Object> loadDetail(long orgId, long outsourcingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, house_label, status, supplier_id,
                       supplier_name, supplier_phone,
                       is_enter, is_lift, is_exceed, is_attic, load_floor, total, charge, rcharge,
                       rbalance, vbalance, remark, created_by_name, assigned_at, delivered_at,
                       received_at, balanced_at, finished_at, cancelled_at, created_at
                from inventory_outsourcing where id = ? and org_id = ?
                """, outsourcingId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.outsourcing.notFound");
        return rows.get(0);
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private static int flag(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
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
