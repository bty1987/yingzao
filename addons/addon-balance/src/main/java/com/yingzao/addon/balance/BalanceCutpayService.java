package com.yingzao.addon.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 扣款池闭环(收编 f384101d3 + 补三守卫,修老 §12-A3/A4/A7):
 * 池原子 SQL 守卫(bind/unbind 全链 `surplus >= :money`/`surplus + :money <= total`,
 * 老池表漏做与 check-then-act 竞态一并消灭)/unbind·deduct 状态守卫(修老未绑先解绑凭空加钱、
 * 0 态记录核负)/金额恒正(修老负数反转全部守卫)。bind-unbind 精确逆操作对称闭环;
 * deduct 子流水 append-only 仅留痕,不参与冲抵计算(修老 amountBalanceCutpay 双计);
 * 返款(type=1)仅字典预留,V1 bind 拒绝。V1 绑定时机统一为结算后(老前后皆可的模糊语义收敛)。
 */
@Service
public class BalanceCutpayService {

    public static final int CODE_POOL_NOT_FOUND = 320009;
    public static final int CODE_POOL_INSUFFICIENT = 320010;
    public static final int CODE_CUTPAY_NOT_FOUND = 320011;
    public static final int CODE_CUTPAY_NOT_BOUND = 320012;
    public static final int CODE_CUTPAY_BOUND = 320013;
    public static final int CODE_MONEY_POSITIVE = 320004;
    public static final int CODE_BAD_STATE = 320002;
    public static final int CODE_FINISHED = 320014;
    public static final int CODE_DEDUCT_EXCEED = 320020;
    public static final int CODE_TYPE_MISMATCH = 320021;

    public static final int CT_DEDUCT = 0;
    public static final int CT_REFUND = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final BalanceSettlementService settlements;

    public BalanceCutpayService(JdbcTemplate jdbc, MessageSource messages,
            BalanceSettlementService settlements) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.settlements = settlements;
    }

    // ==== 池 ====

    public Map<String, Object> pagePools(long orgId, Integer status, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from balance_cutpay_pool" + w, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, type, supplier, total, surplus, status, created_at
                from balance_cutpay_pool""" + w + " order by id desc limit ? offset ?",
                BalanceSettlementService.appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    @Transactional
    public long createPool(long orgId, String name, int type, String supplier, long total,
            long operatorId) {
        if (BalanceSettlementService.notBlank(name)) {
            name = name.trim();
        } else {
            name = "扣款池";
        }
        if (total <= 0) {
            throw new BizException(CODE_MONEY_POSITIVE, msg("balance.money.positive"));
        }
        jdbc.update("""
                insert into balance_cutpay_pool(org_id, name, type, supplier, total, surplus, created_by)
                values (?,?,?,?,?,?,?)
                """, orgId, name, type, BalanceSettlementService.orEmpty(supplier), total, total,
                operatorId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updatePoolStatus(long orgId, long id, int status, long operatorId) {
        loadPool(orgId, id);
        if (status != 0 && status != 1) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
        jdbc.update("update balance_cutpay_pool set status = ? where id = ?", status, id);
    }

    // ==== 扣款单 ====

    public Map<String, Object> pageCutpays(long orgId, Long poolId, Long settlementId, Integer status,
            int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where c.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (poolId != null) {
            where.append(" and c.pool_id = ?");
            args.add(poolId);
        }
        if (settlementId != null) {
            where.append(" and c.settlement_id = ?");
            args.add(settlementId);
        }
        if (status != null) {
            where.append(" and c.status = ?");
            args.add(status);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject(
                "select count(*) from balance_cutpay c" + w, Long.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.pool_id, p.name as pool_name, c.settlement_id, c.money, c.type,
                       c.status, c.deduct_total, c.memo, c.bound_at, c.created_at
                from balance_cutpay c join balance_cutpay_pool p on p.id = c.pool_id""" + w
                + " order by c.id desc limit ? offset ?",
                BalanceSettlementService.appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> cutpayDetail(long orgId, long id) {
        Map<String, Object> row = loadCutpay(orgId, id);
        row.put("pool", loadPool(orgId, ((Number) row.get("pool_id")).longValue()));
        row.put("deducts", jdbc.queryForList("""
                select id, money, memo, created_by, created_at
                from balance_cutpay_deduct where org_id = ? and cutpay_id = ? order by id
                """, orgId, id));
        return row;
    }

    /** 建扣款单(待绑定):仅登记额度,池不动;绑定(bind)才消耗池与结算单。 */
    @Transactional
    public long createCutpay(long orgId, long poolId, long money, int type, String memo,
            long operatorId) {
        if (money <= 0) {
            throw new BizException(CODE_MONEY_POSITIVE, msg("balance.money.positive"));
        }
        if (type != CT_DEDUCT && type != CT_REFUND) {
            throw new BizException(CODE_TYPE_MISMATCH, msg("balance.account.type.mismatch"));
        }
        Map<String, Object> pool = loadPool(orgId, poolId);
        if (((Number) pool.get("status")).intValue() != 0) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
        jdbc.update("""
                insert into balance_cutpay(org_id, pool_id, money, type, memo, created_by)
                values (?,?,?,?,?,?)
                """, orgId, poolId, money, type, BalanceSettlementService.blankToNull(memo),
                operatorId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 绑定:池原子扣额+结算单余量原子冲抵,0初始→1已绑定(仅结算后;返款类型 V1 拒绝)。 */
    @Transactional
    public void bind(long orgId, long id, long settlementId, long operatorId) {
        Map<String, Object> cutpay = loadCutpay(orgId, id);
        if (((Number) cutpay.get("status")).intValue() != 0) {
            throw new BizException(CODE_CUTPAY_BOUND, msg("balance.cutpay.bound"));
        }
        if (((Number) cutpay.get("type")).intValue() != CT_DEDUCT) {
            throw new BizException(CODE_TYPE_MISMATCH, msg("balance.account.type.mismatch"));
        }
        long money = ((Number) cutpay.get("money")).longValue();
        Map<String, Object> pool = loadPool(orgId, ((Number) cutpay.get("pool_id")).longValue());
        if (((Number) pool.get("status")).intValue() != 0) {
            throw new BizException(CODE_BAD_STATE, msg("balance.state.illegal"));
        }
        Map<String, Object> settlement = settlements.load(orgId, settlementId);
        settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
        int poolUpdated = jdbc.update("""
                update balance_cutpay_pool set surplus = surplus - ?
                where id = ? and status = 0 and surplus >= ?
                """, money, pool.get("id"), money);
        if (poolUpdated == 0) {
            throw new BizException(CODE_POOL_INSUFFICIENT, msg("balance.pool.insufficient"));
        }
        int settleUpdated = jdbc.update("""
                update balance_settlement set cutpay = cutpay + ?, surplus = surplus - ?
                where id = ? and status = ? and surplus >= ?
                """, money, money, settlementId, BalanceSettlementService.ST_SETTLED, money);
        if (settleUpdated == 0) {
            throw new BizException(CODE_SURPLUS_SHORT_CODE, msg("balance.settlement.surplus.short"));
        }
        int bindUpdated = jdbc.update("""
                update balance_cutpay set status = 1, settlement_id = ?, bound_at = now()
                where id = ? and status = 0
                """, settlementId, id);
        if (bindUpdated == 0) {
            throw new BizException(CODE_CUTPAY_BOUND, msg("balance.cutpay.bound"));
        }
        settlements.track(orgId, settlementId, "cutpayBind", "扣款冲抵 " + money, operatorId);
    }

    /** 解绑(精确逆操作):回补池+回冲结算单,1已绑定→0初始。 */
    @Transactional
    public void unbind(long orgId, long id, String reason, long operatorId) {
        Map<String, Object> cutpay = loadCutpay(orgId, id);
        if (((Number) cutpay.get("status")).intValue() != 1) {
            throw new BizException(CODE_CUTPAY_NOT_BOUND, msg("balance.cutpay.notBound"));
        }
        if (cutpay.get("settlement_id") == null) {
            throw new BizException(CODE_CUTPAY_NOT_BOUND, msg("balance.cutpay.notBound"));
        }
        long money = ((Number) cutpay.get("money")).longValue();
        long settlementId = ((Number) cutpay.get("settlement_id")).longValue();
        Map<String, Object> settlement = settlements.load(orgId, settlementId);
        settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
        jdbc.update("update balance_cutpay_pool set surplus = surplus + ? where id = ?",
                money, cutpay.get("pool_id"));
        int settleUpdated = jdbc.update("""
                update balance_settlement set cutpay = cutpay - ?, surplus = surplus + ?
                where id = ? and status = ? and cutpay >= ?
                """, money, money, settlementId, BalanceSettlementService.ST_SETTLED, money);
        if (settleUpdated == 0) {
            throw new BizException(CODE_SURPLUS_SHORT_CODE, msg("balance.settlement.surplus.short"));
        }
        jdbc.update("""
                update balance_cutpay set status = 0, settlement_id = null, bound_at = null
                where id = ? and status = 1
                """, id);
        settlements.track(orgId, settlementId, "cutpayUnbind", "扣款解绑回补 " + money
                + BalanceSettlementService.blankSuffix(reason), operatorId);
    }

    /** 子流水(仅留痕):Σdeduct + 本笔 <= money;须已绑定(修老 0 态记录核负)。 */
    @Transactional
    public void deduct(long orgId, long id, long money, String memo, long operatorId) {
        if (money <= 0) {
            throw new BizException(CODE_MONEY_POSITIVE, msg("balance.money.positive"));
        }
        Map<String, Object> cutpay = loadCutpay(orgId, id);
        if (((Number) cutpay.get("status")).intValue() != 1) {
            throw new BizException(CODE_CUTPAY_NOT_BOUND, msg("balance.cutpay.notBound"));
        }
        long total = ((Number) cutpay.get("money")).longValue();
        long deducted = ((Number) cutpay.get("deduct_total")).longValue();
        if (deducted + money > total) {
            throw new BizException(CODE_DEDUCT_EXCEED, msg("balance.deduct.exceed"));
        }
        jdbc.update("""
                insert into balance_cutpay_deduct(org_id, cutpay_id, money, memo, created_by)
                values (?,?,?,?,?)
                """, orgId, id, money, BalanceSettlementService.blankToNull(memo), operatorId);
        jdbc.update("update balance_cutpay set deduct_total = deduct_total + ? where id = ?",
                money, id);
    }

    // ==== 内部 ====

    private static final int CODE_SURPLUS_SHORT_CODE = 320008;

    private Map<String, Object> loadPool(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from balance_cutpay_pool where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_POOL_NOT_FOUND, msg("balance.pool.notFound"));
        }
        return rows.get(0);
    }

    private Map<String, Object> loadCutpay(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from balance_cutpay where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CUTPAY_NOT_FOUND, msg("balance.cutpay.notFound"));
        }
        return rows.get(0);
    }

    String msg(String key) {
        return messages.getMessage(key, null, org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }
}
