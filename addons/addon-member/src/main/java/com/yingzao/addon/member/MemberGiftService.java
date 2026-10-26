package com.yingzao.addon.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.GiftStockChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 礼品主档与库存服务。
 * 修老 AwardStock 全链:①库存扣减 9 处 check-then-act 竞态可超卖 → 一律条件 UPDATE(WHERE stock_num>=x)
 * 检查受影响行数;②no 全局唯一=外部商城商品一等公民,upsert 幂等沿 supplier ext_no 模式
 * (修老 updateMemberGoods 查后插非原子);③去"总部仓硬编码 company=0",按 org 建库存;
 * ④三开关 isPrefor/isStock/isPayment 语义 0=算 1=不算 原样保留;
 * ⑤量纲红线:integral_price=整数积分(老 unitPrice 单列被商城当积分值复用的混乱,拆两列显式化)。
 */
@Service
public class MemberGiftService {

    public static final int CODE_NOT_FOUND = 350002;
    public static final int CODE_STOCK = 350003;
    public static final int CODE_DISABLED = 350004;
    public static final int CODE_STOCKPARAM = 350018;
    public static final int CODE_NO_EXISTS = 350016;
    public static final int CODE_PARAM = 350020;
    // ---- 采购流(A7;350030-350032) ----
    public static final int CODE_PURCHASE_NOT_FOUND = 350030;
    public static final int CODE_PURCHASE_BAD_STATE = 350031;
    public static final int CODE_PURCHASE_SURPLUS = 350032;

    /** 库存流水类型。 */
    public static final int STOCK_IN = 0;
    public static final int STOCK_OUT = 1;
    public static final int STOCK_CHECKIN = 2;
    public static final int STOCK_CHECKOUT = 3;

    // ---- 采购单状态(老 AwardPurchase 状态机原样回归) ----
    public static final int PUR_INIT = 0;
    public static final int PUR_PROC = 1;
    public static final int PUR_WARE = 2;
    public static final int PUR_CLOSE = 3;
    public static final int PUR_DISABLE = 4;
    public static final int PUR_FINISH = 5;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;

    public MemberGiftService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
    }

    /** 建档/同步命令:no 可空(自动 MG+8位按 id 生成);三开关语义 0=算 1=不算。 */
    public record SaveCmd(String no, String name, Long integralPrice, Long unitPrice,
            Integer isPrefor, Integer isStock, Integer isPayment, String remark) {
    }

    /** 库存操作:type 0入库 1出库 2盘盈 3盘亏。 */
    public record StockCmd(Integer type, Long num, String remark) {
    }

    @Transactional
    public long create(long orgId, SaveCmd cmd) {
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_PARAM, "param.invalid");
        require(cmd.integralPrice() != null && cmd.integralPrice() > 0, CODE_PARAM, "gift.price");
        require(switchVal(cmd.isPrefor()) >= 0 && switchVal(cmd.isStock()) >= 0 && switchVal(cmd.isPayment()) >= 0,
                CODE_PARAM, "param.invalid");
        String no = cmd.no() == null || cmd.no().isBlank() ? "MG00000000" : cmd.no().trim();
        try {
            jdbc.update("""
                    insert into member_gift
                        (org_id, no, name, integral_price, unit_price, is_prefor, is_stock, is_payment,
                         stock_num, status, remark)
                    values (?,?,?,?,?,?,?,?,0,0,?)
                    """, orgId, no, cmd.name().trim(), cmd.integralPrice(), cmd.unitPrice(),
                    switchVal(cmd.isPrefor()), switchVal(cmd.isStock()), switchVal(cmd.isPayment()), cmd.remark());
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_NO_EXISTS, msg("gift.noexists"));
        }
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        if (cmd.no() == null || cmd.no().isBlank()) {
            // 编号按 id 生成,无计数器读改写竞态(老 counting 教训)
            jdbc.update("update member_gift set no = concat('MG', lpad(id, 8, '0')) where id = ?", id);
        }
        return id;
    }

    /** 编辑(白名单;no 与库存数不可被 VO 覆写)。 */
    @Transactional
    public void update(long orgId, long id, SaveCmd cmd) {
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "gift.notfound");
        if (cmd.integralPrice() != null) {
            require(cmd.integralPrice() > 0, CODE_PARAM, "gift.price");
        }
        jdbc.update("""
                update member_gift set
                    name = ?, integral_price = ?, unit_price = ?, is_prefor = ?, is_stock = ?, is_payment = ?,
                    remark = ?
                where id = ? and org_id = ?
                """,
                firstNonBlank(cmd.name(), (String) row.get("name")),
                cmd.integralPrice() != null ? cmd.integralPrice() : ((Number) row.get("integral_price")).longValue(),
                cmd.unitPrice() != null ? cmd.unitPrice() : (Long) row.get("unit_price"),
                cmd.isPrefor() != null ? switchVal(cmd.isPrefor()) : ((Number) row.get("is_prefor")).intValue(),
                cmd.isStock() != null ? switchVal(cmd.isStock()) : ((Number) row.get("is_stock")).intValue(),
                cmd.isPayment() != null ? switchVal(cmd.isPayment()) : ((Number) row.get("is_payment")).intValue(),
                cmd.remark() != null ? cmd.remark() : (String) row.get("remark"),
                id, orgId);
    }

    /** 启停(可逆;同态重放幂等)。 */
    @Transactional
    public void changeStatus(long orgId, long id, int status) {
        require(status == 0 || status == 1, CODE_PARAM, "param.invalid");
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "gift.notfound");
        if (((Number) row.get("status")).intValue() == status) {
            return;
        }
        jdbc.update("update member_gift set status = ? where id = ? and org_id = ?", status, id, orgId);
    }

    /**
     * 外部商城目录同步:按 no 幂等 upsert(命中更新目录字段回显 created=false,未命中新建 created=true;
     * 并发同 no 撞唯一键后 DB 兜底重查转更新——沿 supplier ext_no 三态范式)。
     * 不改 status 与 stock_num(库存与启停是 ERP 侧事实,商城目录同步不覆盖)。
     */
    @Transactional
    public Map<String, Object> upsert(long orgId, SaveCmd cmd) {
        require(cmd.no() != null && !cmd.no().isBlank(), CODE_PARAM, "param.invalid");
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_PARAM, "param.invalid");
        require(cmd.integralPrice() != null && cmd.integralPrice() > 0, CODE_PARAM, "gift.price");
        Map<String, Object> existing = findByNo(orgId, cmd.no().trim());
        if (existing != null) {
            applySyncUpdate(orgId, ((Number) existing.get("id")).longValue(), cmd);
            return echo(existing, false);
        }
        try {
            long id = create(orgId, cmd);
            return Map.of("id", id, "created", true);
        } catch (BizException e) {
            if (e.getCode() != CODE_NO_EXISTS) {
                throw e;
            }
            // 并发同 no:DB 兜底重查转更新
            Map<String, Object> raced = findByNo(orgId, cmd.no().trim());
            require(raced != null, CODE_NO_EXISTS, "gift.noexists");
            applySyncUpdate(orgId, ((Number) raced.get("id")).longValue(), cmd);
            return echo(raced, false);
        }
    }

    /** 手工出入库/盘点:type 0入/1出/2盘盈/3盘亏;出与盘亏条件减(不足 350003)。 */
    @Transactional
    public Map<String, Object> stockOp(long orgId, long giftId, StockCmd cmd, Long userId) {
        require(cmd.type() != null && cmd.type() >= 0 && cmd.type() <= 3, CODE_STOCKPARAM, "gift.stockparam");
        require(cmd.num() != null && cmd.num() > 0, CODE_STOCKPARAM, "gift.stockparam");
        Map<String, Object> gift = find(orgId, giftId);
        require(gift != null, CODE_NOT_FOUND, "gift.notfound");
        int type = cmd.type();
        String subtype = type == STOCK_IN ? "manual" : type == STOCK_OUT ? "manual"
                : type == STOCK_CHECKIN ? "manual" : "manual";
        long after = type == STOCK_IN || type == STOCK_CHECKIN
                ? increase(orgId, giftId, cmd.num())
                : decrease(orgId, giftId, cmd.num(), CODE_STOCK, "gift.stock");
        long recordId = writeRecord(orgId, giftId, (String) gift.get("no"), type, subtype, cmd.num(), after,
                null, null, null, cmd.remark(), userId);
        publishStock(recordId, giftId, orgId, (String) gift.get("no"), type, cmd.num(), after, null, null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("stockNum", after);
        return result;
    }

    // ---- 兑换联动内部口(调用方持有事务) ----

    /** 兑换扣库存(isStock=0 才调;原子条件扣,不足 350003)。 */
    public void deductForExchange(long orgId, long giftId, String giftNo, long num,
            String bizAddon, String bizType, String bizId) {
        long after = decrease(orgId, giftId, num, CODE_STOCK, "gift.stock");
        long recordId = writeRecord(orgId, giftId, giftNo, STOCK_OUT, "exchange", num, after,
                bizAddon, bizType, bizId, "积分商城兑换", null);
        publishStock(recordId, giftId, orgId, giftNo, STOCK_OUT, num, after, bizAddon, bizType, bizId);
    }

    /** 撤销回补库存(反向流水)。 */
    public void restoreForRevoke(long orgId, long giftId, String giftNo, long num,
            String bizAddon, String bizType, String bizId) {
        long after = increase(orgId, giftId, num);
        long recordId = writeRecord(orgId, giftId, giftNo, STOCK_IN, "revoke", num, after,
                bizAddon, bizType, bizId, "兑换单撤销回补", null);
        publishStock(recordId, giftId, orgId, giftNo, STOCK_IN, num, after, bizAddon, bizType, bizId);
    }

    // ---- 查询 ----

    public Map<String, Object> get(long orgId, long id) {
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "gift.notfound");
        return row;
    }

    /**
     * 按编号精确取(兑换落单用;🔴 禁用 like 检索取单行——MG001 会误中 MG0011,
     * 编号是外部商城一等公民,必须等值匹配)。
     */
    public Map<String, Object> getByNo(long orgId, String no) {
        Map<String, Object> row = findByNo(orgId, no);
        require(row != null, CODE_NOT_FOUND, "gift.notfound");
        return row;
    }

    public Map<String, Object> page(long orgId, Integer status, String keyword, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (name like concat('%', ?, '%') or no like concat('%', ?, '%'))");
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from member_gift" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, no, name, integral_price, unit_price, is_prefor, is_stock, is_payment,"
                + " stock_num, status, remark, created_at, updated_at from member_gift" + where
                + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 采购流(A7:父单链分批入库;老 AwardPurchase 状态机回归) ----

    /** 采购登记命令。 */
    public record PurchaseCmd(Long giftId, Long num, String remark) {
    }

    public Map<String, Object> pagePurchases(long orgId, Integer status, Long giftId,
            int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (giftId != null) {
            where.append(" and gift_id = ?");
            args.add(giftId);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from member_gift_purchase" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, gift_id, gift_no, gift_name, father_id, num, surplus, inprice, baprice,
                       status, remark, creater_id, purchaser_id, instorer_id, purchase_time,
                       instore_time, balance_time, created_at
                from member_gift_purchase""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    /** 采购申请(主单,status=0,surplus=num)。 */
    @Transactional
    public long createPurchase(long orgId, PurchaseCmd cmd, long userId) {
        require(cmd != null && cmd.giftId() != null && cmd.num() != null && cmd.num() > 0,
                CODE_PARAM, "param.invalid");
        Map<String, Object> gift = find(orgId, cmd.giftId());
        require(gift != null, CODE_NOT_FOUND, "gift.notfound");
        jdbc.update("""
                insert into member_gift_purchase(org_id, gift_id, gift_no, gift_name, num, surplus,
                    status, remark, creater_id)
                values (?,?,?,?,?,?,0,?,?)
                """, orgId, cmd.giftId(), gift.get("no"), gift.get("name"), cmd.num(), cmd.num(),
                blankToNull(cmd.remark()), userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 采购(0→1,登记采购人;条件更新修老裸状态写)。 */
    @Transactional
    public void purchase(long orgId, long purchaseId, long userId) {
        int updated = jdbc.update("""
                update member_gift_purchase set status = 1, purchaser_id = ?, purchase_time = now()
                where id = ? and org_id = ? and status = 0 and father_id is null
                """, userId, purchaseId, orgId);
        if (updated == 0) {
            throwPurchaseState(orgId, purchaseId);
        }
    }

    /**
     * 分批入库(父单 status=1):每批新建子单(father_id=父,status=2,surplus=0);
     * 父 surplus 条件扣减(WHERE surplus>=本次,修老并发丢更新);扣后=0 → 父置 5 完成;
     * gift is_stock=0(算库存)→ 库存回加+记采购入库流水。
     */
    @Transactional
    public long instore(long orgId, long purchaseId, Long num, Long inprice, long userId) {
        require(num != null && num > 0, CODE_PARAM, "param.invalid");
        Map<String, Object> father = findPurchase(orgId, purchaseId);
        if (((Number) father.get("status")).intValue() != PUR_PROC
                || father.get("father_id") != null) {
            throw new BizException(CODE_PURCHASE_BAD_STATE, msg("gift.purchase.badState"));
        }
        int deducted = jdbc.update(
                "update member_gift_purchase set surplus = surplus - ?, instorer_id = ?,"
                        + " instore_time = coalesce(instore_time, now())"
                        + " where id = ? and org_id = ? and status = 1 and surplus >= ?",
                num, userId, purchaseId, orgId, num);
        if (deducted == 0) {
            throw new BizException(CODE_PURCHASE_SURPLUS, msg("gift.purchase.surplus"));
        }
        Map<String, Object> after = findPurchase(orgId, purchaseId);
        long surplusAfter = ((Number) after.get("surplus")).longValue();
        if (surplusAfter == 0) {
            jdbc.update("update member_gift_purchase set status = 5 where id = ? and org_id = ?",
                    purchaseId, orgId);
        }
        jdbc.update("""
                insert into member_gift_purchase(org_id, gift_id, gift_no, gift_name, father_id, num,
                    surplus, inprice, status, creater_id, purchaser_id, instorer_id, purchase_time,
                    instore_time)
                select org_id, gift_id, gift_no, gift_name, id, ?, 0, ?, 2, creater_id, purchaser_id, ?,
                       purchase_time, now()
                from member_gift_purchase where id = ? and org_id = ?
                """, num, inprice, userId, purchaseId, orgId);
        long childId = jdbc.queryForObject("select last_insert_id()", Long.class);
        long giftId = ((Number) father.get("gift_id")).longValue();
        Map<String, Object> gift = find(orgId, giftId);
        // is_stock=0 = 算库存:入库回加+流水(与老 ISSTOCK_CAL_INT 同口径)
        if (((Number) gift.get("is_stock")).intValue() == 0) {
            long stockAfter = increase(orgId, giftId, num);
            writeRecord(orgId, giftId, (String) gift.get("no"), STOCK_IN, "purchase", num, stockAfter,
                    "member", "purchase", String.valueOf(childId), null, userId);
            publishStock(0L, giftId, orgId, (String) gift.get("no"), STOCK_IN, num, stockAfter,
                    "member", "purchase", String.valueOf(childId));
        }
        return childId;
    }

    /** 结算(子单 2→3,登记结算价)。 */
    @Transactional
    public void balance(long orgId, long purchaseId, Long baprice, long userId) {
        require(baprice != null && baprice >= 0, CODE_PARAM, "param.invalid");
        int updated = jdbc.update("""
                update member_gift_purchase set baprice = ?, status = 3, balance_time = now()
                where id = ? and org_id = ? and status = 2 and father_id is not null
                """, baprice, purchaseId, orgId);
        if (updated == 0) {
            throwPurchaseState(orgId, purchaseId);
        }
    }

    /** 失效(仅主单初始态 0→4)。 */
    @Transactional
    public void cancelPurchase(long orgId, long purchaseId, long userId) {
        int updated = jdbc.update("""
                update member_gift_purchase set status = 4
                where id = ? and org_id = ? and status = 0 and father_id is null
                """, purchaseId, orgId);
        if (updated == 0) {
            throwPurchaseState(orgId, purchaseId);
        }
    }

    /** 采购单详情(主单带入库子单链)。 */
    public Map<String, Object> purchaseDetail(long orgId, long purchaseId) {
        Map<String, Object> purchase = findPurchase(orgId, purchaseId);
        purchase.put("children", jdbc.queryForList("""
                select id, num, inprice, baprice, status, instorer_id, instore_time, balance_time
                from member_gift_purchase where father_id = ? and org_id = ? order by id
                """, purchaseId, orgId));
        return purchase;
    }

    private Map<String, Object> findPurchase(long orgId, long purchaseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from member_gift_purchase where id = ? and org_id = ?", purchaseId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_PURCHASE_NOT_FOUND, msg("gift.purchase.notFound"));
        }
        return rows.get(0);
    }

    private void throwPurchaseState(long orgId, long purchaseId) {
        findPurchase(orgId, purchaseId);
        throw new BizException(CODE_PURCHASE_BAD_STATE, msg("gift.purchase.badState"));
    }

    public Map<String, Object> records(long orgId, long giftId, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject(
                "select count(*) from member_gift_record where org_id = ? and gift_id = ?",
                Long.class, orgId, giftId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, gift_id, gift_no, type, subtype, num, after_num, biz_addon, biz_type, biz_id,"
                + " remark, created_at from member_gift_record where org_id = ? and gift_id = ?"
                + " order by id desc limit ? offset ?", orgId, giftId, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 内部 ----

    private void applySyncUpdate(long orgId, long id, SaveCmd cmd) {
        jdbc.update("""
                update member_gift set name = ?, integral_price = ?, unit_price = ?,
                    is_prefor = ?, is_stock = ?, is_payment = ?
                where id = ? and org_id = ?
                """, cmd.name().trim(), cmd.integralPrice(), cmd.unitPrice(),
                switchVal(cmd.isPrefor()), switchVal(cmd.isStock()), switchVal(cmd.isPayment()), id, orgId);
    }

    private Map<String, Object> echo(Map<String, Object> gift, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ((Number) gift.get("id")).longValue());
        result.put("created", created);
        return result;
    }

    private long increase(long orgId, long giftId, long num) {
        jdbc.update("update member_gift set stock_num = stock_num + ? where id = ? and org_id = ?",
                num, giftId, orgId);
        return currentStock(orgId, giftId);
    }

    private long decrease(long orgId, long giftId, long num, int errorCode, String msgKey) {
        int updated = jdbc.update(
                "update member_gift set stock_num = stock_num - ? where id = ? and org_id = ? and stock_num >= ?",
                num, giftId, orgId, num);
        if (updated == 0) {
            throw new BizException(errorCode, msg(msgKey));
        }
        return currentStock(orgId, giftId);
    }

    private long currentStock(long orgId, long giftId) {
        return jdbc.queryForObject("select stock_num from member_gift where id = ?", Long.class, giftId);
    }

    private long writeRecord(long orgId, long giftId, String giftNo, int type, String subtype, long num,
            long afterNum, String bizAddon, String bizType, String bizId, String remark, Long userId) {
        jdbc.update("""
                insert into member_gift_record
                    (org_id, gift_id, gift_no, type, subtype, num, after_num, biz_addon, biz_type, biz_id, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, giftId, giftNo, type, subtype, num, afterNum, bizAddon, bizType, bizId, remark, userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void publishStock(long recordId, long giftId, long orgId, String giftNo, int type, long num,
            long afterNum, String bizAddon, String bizType, String bizId) {
        String action = type == STOCK_IN ? "in" : type == STOCK_OUT ? "out"
                : type == STOCK_CHECKIN ? "checkIn" : "checkOut";
        if ("exchange".equals(bizType)) {
            action = "exchange";
        } else if ("exchange_revoke".equals(bizType)) {
            action = "revoke";
        }
        long change = (type == STOCK_IN || type == STOCK_CHECKIN) ? num : -num;
        bus.publish(new GiftStockChangedEvent(giftId, orgId, giftNo, action, num, change, afterNum,
                bizAddon, bizType, bizId), "member-gift-stock:" + recordId);
    }

    private Map<String, Object> find(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, no, name, integral_price, unit_price, is_prefor, is_stock, is_payment,"
                + " stock_num, status, remark, created_at, updated_at from member_gift where id = ? and org_id = ?",
                id, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findByNo(long orgId, String no) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, no, name, integral_price, unit_price, is_prefor, is_stock, is_payment,"
                + " stock_num, status, remark from member_gift where no = ? and org_id = ?", no, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int switchVal(Integer v) {
        if (v == null) {
            return 0;
        }
        require(v == 0 || v == 1, CODE_PARAM, "param.invalid");
        return v;
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
