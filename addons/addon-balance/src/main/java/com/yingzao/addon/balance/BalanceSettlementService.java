package com.yingzao.addon.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.SettlementItemReleasedEvent;
import com.yingzao.addons.api.event.SettlementItemReadyEvent;
import com.yingzao.addons.api.event.SettlementItemWithdrawnEvent;
import com.yingzao.addons.api.event.SettlementSettledEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 结算单主链(统一模型,三来源共用)。结算汇聚域铁律:本域不产生金额,四项 income/cost/
 * payment/profit 全部由上游「明细可结算」事件携带的快照行服务端加总(修老动态聚合漂移与
 * 三处信任前端);零上游知识——不 import 任何上游 service,不直查上游表(军规 2)。
 * 状态机:0初始→1锁定→2待审(可选)→3已结算→4已完结;解锁仅 1→0;终态后全部写操作拒绝。
 * 快照行 uk(source 三元组)锁死同一明细全局只挂一单(修老外销绑入无防重利润重复累加);
 * 重放幂等:同键同金额静默跳过,同键异金额=上游修正(仅初始态)。
 */
@Service
public class BalanceSettlementService {

    public static final int CODE_NOT_FOUND = 320001;
    public static final int CODE_BAD_STATE = 320002;
    public static final int CODE_ITEM_DUP = 320003;
    public static final int CODE_MONEY_POSITIVE = 320004;
    public static final int CODE_FINISHED = 320014;
    public static final int CODE_DISCOUNT = 320017;
    public static final int CODE_WITHDRAW_LOCKED = 320018;
    public static final int CODE_DELETE_HAS_ITEMS = 320019;
    public static final int CODE_ITEM_NOT_FOUND = 320023;
    public static final int CODE_PARAM = 320025;

    public static final int ST_INIT = 0;
    public static final int ST_LOCKED = 1;
    public static final int ST_AUDITING = 2;
    public static final int ST_SETTLED = 3;
    public static final int ST_FINISHED = 4;

    public static final String PREFIX_JS = "JS";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;

    public BalanceSettlementService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
    }

    // ==== 事件侧(总线 SPI 经 SettlementItemConsumer 调入) ====

    /** 明细可结算:找同键初始桶聚桶(无则建),落快照行,服务端重算四项。 */
    @Transactional
    public void onItemReady(SettlementItemReadyEvent e) {
        require(e.orgId() != null, CODE_PARAM, "balance.param.invalid");
        require(notBlank(e.sourceAddon()) && notBlank(e.sourceType()) && notBlank(e.sourceItemId()),
                CODE_PARAM, "balance.param.invalid");
        require(e.income() != null && e.cost() != null && e.payment() != null,
                CODE_PARAM, "balance.param.invalid");
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select * from balance_settlement_item
                where source_addon = ? and source_type = ? and source_item_id = ?
                """, e.sourceAddon(), e.sourceType(), e.sourceItemId());
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (sameSnapshot(row, e)) {
                return;
            }
            long sid = ((Number) row.get("settlement_id")).longValue();
            Map<String, Object> bucket = load(((Number) row.get("org_id")).longValue(), sid);
            requireStatus(bucket, ST_INIT);
            jdbc.update("""
                    update balance_settlement_item set org_id = ?, qty = ?, price = ?, income = ?,
                        cost = ?, payment = ?, memo = ? where id = ?
                    """, e.orgId(), nvl(e.qty()), nvl(e.price()), e.income(), e.cost(), e.payment(),
                    blankToNull(e.memo()), row.get("id"));
            recompute(((Number) row.get("org_id")).longValue(), sid);
            return;
        }
        long orgId = e.orgId();
        String supplier = orEmpty(e.supplier());
        String grade = notBlank(e.grade()) ? e.grade().trim() : "A";
        String settleType = orEmpty(e.sourceAddon()) + ":" + orEmpty(e.sourceType());
        // 同键初始桶:select for update 串行化建桶,修老并发可建重复桶(§12-B3)
        List<Map<String, Object>> buckets = jdbc.queryForList("""
                select id from balance_settlement
                where org_id = ? and settlement_type = ? and brand_id = ? and supplier = ? and grade = ?
                  and status = 0 order by id limit 1 for update
                """, orgId, settleType, nvl(e.brandId()), supplier, grade);
        long sid;
        long operatorId = 0;
        if (buckets.isEmpty()) {
            String no = PREFIX_JS + codeDate() + String.format("%04d", seq(orgId));
            String name = bucketName(e, supplier, grade);
            jdbc.update("""
                    insert into balance_settlement(org_id, no, settlement_type, name, brand_id,
                        brand_name, supplier, supplier_name, grade, status, created_by)
                    values (?,?,?,?,?,?,?,?,?,0,0)
                    """, orgId, no, settleType, name, nvl(e.brandId()),
                    orEmpty(e.brandName()), supplier, orEmpty(e.supplierName()), grade);
            sid = jdbc.queryForObject("select last_insert_id()", Long.class);
            track(orgId, sid, "ready", "吸附明细 " + e.sourceAddon() + "/" + e.sourceType()
                    + "/" + e.sourceItemId(), operatorId);
        } else {
            sid = ((Number) buckets.get(0).get("id")).longValue();
        }
        try {
            jdbc.update("""
                    insert into balance_settlement_item(org_id, settlement_id, source_addon,
                        source_type, source_item_id, qty, price, income, cost, payment, memo)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, sid, e.sourceAddon(), e.sourceType(), e.sourceItemId(),
                    nvl(e.qty()), nvl(e.price()), e.income(), e.cost(), e.payment(),
                    blankToNull(e.memo()));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BizException(CODE_ITEM_DUP, msg("balance.item.dup"));
        }
        recompute(orgId, sid);
    }

    /** 明细退结算:未锁定桶摘行重算;已锁定/已结算拒绝(死信可见);从未吸附幂等跳过。 */
    @Transactional
    public void onItemWithdrawn(SettlementItemWithdrawnEvent e) {
        require(e.orgId() != null, CODE_PARAM, "balance.param.invalid");
        List<Map<String, Object>> items = jdbc.queryForList("""
                select * from balance_settlement_item
                where source_addon = ? and source_type = ? and source_item_id = ?
                """, orEmpty(e.sourceAddon()), orEmpty(e.sourceType()), orEmpty(e.sourceItemId()));
        if (items.isEmpty()) {
            return;
        }
        Map<String, Object> item = items.get(0);
        long orgId = ((Number) item.get("org_id")).longValue();
        long sid = ((Number) item.get("settlement_id")).longValue();
        Map<String, Object> bucket = load(orgId, sid);
        requireStatus(bucket, ST_INIT);
        jdbc.update("delete from balance_settlement_item where id = ?", item.get("id"));
        recompute(orgId, sid);
        track(orgId, sid, "withdrawn", "上游退回明细 " + e.sourceAddon() + "/" + e.sourceType()
                + "/" + e.sourceItemId() + blankSuffix(e.reason()), 0);
    }

    // ==== 读侧 ====

    public Map<String, Object> page(long orgId, String settlementType, Integer status, Long brandId,
            String no, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (notBlank(settlementType)) {
            where.append(" and settlement_type = ?");
            args.add(settlementType.trim());
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (brandId != null) {
            where.append(" and brand_id = ?");
            args.add(brandId);
        }
        if (notBlank(no)) {
            where.append(" and no like ?");
            args.add("%" + no.trim() + "%");
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from balance_settlement" + w, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, no, settlement_type, name, brand_id, brand_name, supplier, supplier_name,
                       grade, status, income, cost, payment, profit, discount, amount, cutpay,
                       surplus, paid, is_pay, item_count, settled_at, finished_at, created_at
                from balance_settlement""" + w + " order by id desc limit ? offset ?",
                appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long id) {
        Map<String, Object> row = load(orgId, id);
        row.put("items", jdbc.queryForList("""
                select id, source_addon, source_type, source_item_id, qty, price, income, cost,
                       payment, memo, created_at
                from balance_settlement_item where org_id = ? and settlement_id = ? order by id
                """, orgId, id));
        row.put("tracks", jdbc.queryForList("""
                select action, detail, operator_id, operator_name, created_at
                from balance_settlement_track where org_id = ? and settlement_id = ? order by id
                """, orgId, id));
        return row;
    }

    // ==== 流转(REST) ====

    @Transactional
    public void lock(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_INIT);
        jdbc.update("update balance_settlement set status = 1 where id = ? and status = 0", id);
        track(orgId, id, "lock", "锁定(金额快照定格)", operatorId);
    }

    @Transactional
    public void unlock(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_LOCKED);
        jdbc.update("update balance_settlement set status = 0 where id = ? and status = 1", id);
        track(orgId, id, "unlock", "解锁退回初始", operatorId);
    }

    @Transactional
    public void audit(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_LOCKED);
        jdbc.update("update balance_settlement set status = 2 where id = ? and status = 1", id);
        track(orgId, id, "audit", "送审", operatorId);
    }

    @Transactional
    public void unaudit(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_AUDITING);
        jdbc.update("update balance_settlement set status = 1 where id = ? and status = 2", id);
        track(orgId, id, "unaudit", "驳回退锁定", operatorId);
    }

    /** 结算(终态前置):应付=payment-discount 须大于零;状态 1/2 → 3;发布已结算事件。 */
    @Transactional
    public void settle(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        int status = ((Number) row.get("status")).intValue();
        if (status != ST_LOCKED && status != ST_AUDITING) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
        long amount = ((Number) row.get("amount")).longValue();
        if (amount <= 0) {
            throw new BizException(CODE_MONEY_POSITIVE, msg("balance.money.positive"));
        }
        int updated = jdbc.update("""
                update balance_settlement set status = 3, surplus = amount, settled_by = ?,
                    settled_at = now() where id = ? and status in (1, 2)
                """, operatorId, id);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
        track(orgId, id, "settle", "结算入账,应付 " + amount, operatorId);
        eventBus.publish(new SettlementSettledEvent(id, orgId, String.valueOf(row.get("no")),
                String.valueOf(row.get("settlement_type")), String.valueOf(row.get("brand_name")),
                String.valueOf(row.get("supplier")), String.valueOf(row.get("supplier_name")),
                amount, operatorId), "balance:settlement:" + id + ":settled");
    }

    /** 整单让减:仅初始态;0<=x<=payment;重算 amount。 */
    @Transactional
    public void updateDiscount(long orgId, long id, long discount, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_INIT);
        long payment = ((Number) row.get("payment")).longValue();
        if (discount < 0 || discount > payment) {
            throw new BizException(CODE_DISCOUNT, msg("balance.discount.invalid"));
        }
        jdbc.update("""
                update balance_settlement set discount = ?, amount = payment - ? where id = ?
                """, discount, discount, id);
        track(orgId, id, "discount", "整单让减 " + discount, operatorId);
    }

    /** 解绑明细(仅初始态):摘行重算+发布退回事件,上游自行回置对账状态(零直写上游)。 */
    @Transactional
    public void unbindItem(long orgId, long id, String sourceAddon, String sourceType,
            String sourceItemId, String reason, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_INIT);
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id from balance_settlement_item
                where org_id = ? and settlement_id = ? and source_addon = ? and source_type = ?
                  and source_item_id = ?
                """, orgId, id, orEmpty(sourceAddon), orEmpty(sourceType), orEmpty(sourceItemId));
        if (items.isEmpty()) {
            throw new BizException(CODE_ITEM_NOT_FOUND, msg("balance.item.notFound"));
        }
        jdbc.update("delete from balance_settlement_item where id = ?", items.get(0).get("id"));
        recompute(orgId, id);
        track(orgId, id, "unbindItem", "解绑明细 " + sourceAddon + "/" + sourceType + "/"
                + sourceItemId + blankSuffix(reason), operatorId);
        eventBus.publish(new SettlementItemReleasedEvent(orgId, sourceAddon, sourceType,
                sourceItemId, id, String.valueOf(row.get("no")),
                SettlementItemReleasedEvent.ACTION_UNBIND, blankToNull(reason)),
                "settle-item:" + sourceAddon + ":" + sourceType + ":" + sourceItemId + ":released");
    }

    /** 删除空桶:仅初始态且零明细(老 delete 不清伴生资金头——本域资金头内联,无孤儿问题)。 */
    @Transactional
    public void delete(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, ST_INIT);
        int items = ((Number) row.get("item_count")).intValue();
        if (items > 0) {
            throw new BizException(CODE_DELETE_HAS_ITEMS, msg("balance.delete.hasItems"));
        }
        jdbc.update("delete from balance_settlement_track where org_id = ? and settlement_id = ?",
                orgId, id);
        jdbc.update("delete from balance_settlement where id = ? and org_id = ?", id, orgId);
    }

    // ==== 内部 ====

    /** 四项服务端加总:income/cost/payment=Σ快照行,profit=income-payment,amount=payment-discount。 */
    private void recompute(long orgId, long sid) {
        Map<String, Object> sums = jdbc.queryForMap("""
                select ifnull(sum(income), 0) as income, ifnull(sum(cost), 0) as cost,
                       ifnull(sum(payment), 0) as payment, count(*) as cnt
                from balance_settlement_item where org_id = ? and settlement_id = ?
                """, orgId, sid);
        long income = ((Number) sums.get("income")).longValue();
        long cost = ((Number) sums.get("cost")).longValue();
        long payment = ((Number) sums.get("payment")).longValue();
        int cnt = ((Number) sums.get("cnt")).intValue();
        jdbc.update("""
                update balance_settlement set income = ?, cost = ?, payment = ?, profit = ?,
                    amount = ? - discount, item_count = ? where id = ?
                """, income, cost, payment, income - payment, payment, cnt, sid);
    }

    private boolean sameSnapshot(Map<String, Object> row, SettlementItemReadyEvent e) {
        return ((Number) row.get("org_id")).longValue() == e.orgId()
                && ((Number) row.get("income")).longValue() == e.income()
                && ((Number) row.get("cost")).longValue() == e.cost()
                && ((Number) row.get("payment")).longValue() == e.payment()
                && ((Number) row.get("qty")).longValue() == nvl(e.qty())
                && ((Number) row.get("price")).longValue() == nvl(e.price());
    }

    private String bucketName(SettlementItemReadyEvent e, String supplier, String grade) {
        String label = notBlank(e.brandName()) ? e.brandName() : "未分组";
        String owner = notBlank(e.supplierName()) ? e.supplierName() : "未指定";
        return label + "-" + owner + "-" + grade + "结算";
    }

    public Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from balance_settlement where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("balance.notFound"));
        }
        return rows.get(0);
    }

    public void requireStatus(Map<String, Object> row, int expected) {
        if (((Number) row.get("status")).intValue() != expected) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
    }

    void requireNotFinished(Map<String, Object> row) {
        if (((Number) row.get("status")).intValue() == ST_FINISHED) {
            throw new BizException(CODE_FINISHED, msg("balance.finished"));
        }
    }

    void track(long orgId, long sid, String action, String detail, long operatorId) {
        jdbc.update("""
                insert into balance_settlement_track(org_id, settlement_id, action, detail,
                    operator_id, operator_name) values (?,?,?,?,?,?)
                """, orgId, sid, action, blankToNull(detail), operatorId, operatorName(operatorId));
    }

    private long seq(long orgId) {
        jdbc.update("""
                insert into balance_counter(org_id, prefix, date_key, last_seq)
                values (?,?,?,last_insert_id(1))
                on duplicate key update last_seq = last_insert_id(last_seq + 1)
                """, orgId, PREFIX_JS, seqDate());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private String codeDate() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    private int seqDate() {
        return Integer.parseInt(codeDate());
    }

    String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    void require(boolean condition, int code, String key) {
        if (!condition) {
            throw new BizException(code, msg(key));
        }
    }

    String operatorName(long userId) {
        List<String> names = jdbc.queryForList(
                "select display_name from yz_user where id = ?", String.class, userId);
        return names.isEmpty() ? (userId == 0 ? "系统" : String.valueOf(userId)) : names.get(0);
    }

    static long nvl(Long v) {
        return v == null ? 0 : v;
    }

    static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String blankSuffix(String v) {
        return notBlank(v) ? ":" + v.trim() : "";
    }

    static List<Object> appendArgs(List<Object> args, Object... extra) {
        List<Object> all = new ArrayList<>(args);
        for (Object o : extra) {
            all.add(o);
        }
        return all;
    }
}
