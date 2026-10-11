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
 * 结算账户与资金双腿(收编 f384101d3 正面教材,生产零存量包袱直接按终态实现):
 * 台账腿 enterSettlement = 归属人结算账户 refer/surplus 原子累加 + 结算单 surplus 原子消耗
 * (`surplus >= :money`,修老 350030 语义挪用正名);出纳现金腿 payCash = 出纳账户原子扣减
 * (余额不足显式拒绝)+ 归属账户懒开户同步(无守卫允许透支=预付,老语义保留)+ 流水留痕;
 * 现金腿不消耗结算单余量(修老两写点完结冲突的根源之一)。付款标批量全或无(修老对 null 静默
 * continue),完结唯一定义 = surplus 清零且已付款,单向无逆向。
 * 账户净头寸不变式:surplus = refer_money - real_money(冒烟对账断言)。
 */
@Service
public class BalanceAccountService {

    public static final int CODE_ACCOUNT_NOT_FOUND = 320005;
    public static final int CODE_ACCOUNT_DISABLED = 320006;
    public static final int CODE_ACCOUNT_INSUFFICIENT = 320007;
    public static final int CODE_SURPLUS_SHORT = 320008;
    public static final int CODE_MONEY_POSITIVE = 320004;
    public static final int CODE_FINISHED = 320014;
    public static final int CODE_PAYMARK_DUP = 320015;
    public static final int CODE_FINISH_GUARD = 320016;
    public static final int CODE_TYPE_MISMATCH = 320021;
    public static final int CODE_BELONGER_MISMATCH = 320022;
    public static final int CODE_ACCOUNT_DUP = 320024;

    public static final int TYPE_SETTLE = 0;
    public static final int TYPE_CASHIER = 1;

    public static final String ACT_CASH_IN = "cashIn";
    public static final String ACT_SETTLE_ENTER = "settleEnter";
    public static final String ACT_PAY_CASH = "payCash";
    public static final String ACT_LAZY_SYNC = "lazySync";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final BalanceSettlementService settlements;

    public BalanceAccountService(JdbcTemplate jdbc, MessageSource messages,
            BalanceSettlementService settlements) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.settlements = settlements;
    }

    // ==== 账户 ====

    public Map<String, Object> page(long orgId, String supplier, Integer type, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (BalanceSettlementService.notBlank(supplier)) {
            where.append(" and belonger = ?");
            args.add(supplier.trim());
        }
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from balance_account" + w, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, belonger, belonger_name, type, refer_money, real_money, surplus,
                       status, created_at
                from balance_account""" + w + " order by id desc limit ? offset ?",
                BalanceSettlementService.appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> details(long orgId, long accountId) {
        load(orgId, accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", jdbc.queryForList("""
                select d.action, d.money, d.balance_after, d.settlement_id, d.memo,
                       d.operator_id, d.created_at
                from balance_account_detail d where d.org_id = ? and d.account_id = ?
                order by d.id desc limit 200
                """, orgId, accountId));
        return result;
    }

    @Transactional
    public long create(long orgId, String belonger, String belongerName, int type, long operatorId) {
        if (type != TYPE_SETTLE && type != TYPE_CASHIER) {
            throw new BizException(CODE_TYPE_MISMATCH, msg("balance.account.type.mismatch"));
        }
        String owner = BalanceSettlementService.orEmpty(belonger);
        try {
            jdbc.update("""
                    insert into balance_account(org_id, belonger, belonger_name, type, created_by)
                    values (?,?,?,?,?)
                    """, orgId, owner, BalanceSettlementService.orEmpty(belongerName), type, operatorId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(CODE_ACCOUNT_DUP, msg("balance.account.dup"));
        }
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateStatus(long orgId, long id, int status, long operatorId) {
        load(orgId, id);
        if (status != 0 && status != 1) {
            throw new BizException(CODE_TYPE_MISMATCH, msg("balance.account.type.mismatch"));
        }
        jdbc.update("update balance_account set status = ? where id = ?", status, id);
    }

    /** 出纳入金(老收款单语义):refer/surplus 同加,现金腿的补足通道。 */
    @Transactional
    public void cashIn(long orgId, long id, long money, String memo, long operatorId) {
        requirePositive(money);
        Map<String, Object> account = load(orgId, id);
        requireType(account, TYPE_CASHIER);
        requireEnabled(account);
        jdbc.update("""
                update balance_account set refer_money = refer_money + ?, surplus = surplus + ?
                where id = ?
                """, money, money, id);
        long after = ((Number) load(orgId, id).get("surplus")).longValue();
        jdbc.update("""
                insert into balance_account_detail(org_id, account_id, settlement_id, action, money,
                    balance_after, memo, operator_id) values (?,?,null,?,?,?,?,?)
                """, orgId, id, ACT_CASH_IN, money, after,
                BalanceSettlementService.blankToNull(memo), operatorId);
    }

    // ==== 双腿 ====

    /** 台账腿入账:归属人结算账户应付登记 + 结算单余量原子消耗(须结算后、归属一致)。 */
    @Transactional
    public void enterSettlement(long orgId, long settlementId, long accountId, long money,
            long operatorId) {
        requirePositive(money);
        Map<String, Object> settlement = settlements.load(orgId, settlementId);
        settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
        Map<String, Object> account = load(orgId, accountId);
        requireType(account, TYPE_SETTLE);
        requireEnabled(account);
        String owner = String.valueOf(settlement.get("supplier"));
        if (!owner.equals(String.valueOf(account.get("belonger")))) {
            throw new BizException(CODE_BELONGER_MISMATCH, msg("balance.belonger.mismatch"));
        }
        jdbc.update("""
                update balance_account set refer_money = refer_money + ?, surplus = surplus + ?
                where id = ?
                """, money, money, accountId);
        int updated = jdbc.update("""
                update balance_settlement set surplus = surplus - ?
                where id = ? and status = ? and surplus >= ?
                """, money, settlementId, BalanceSettlementService.ST_SETTLED, money);
        if (updated == 0) {
            throw new BizException(CODE_SURPLUS_SHORT, msg("balance.settlement.surplus.short"));
        }
        long after = ((Number) load(orgId, accountId).get("surplus")).longValue();
        jdbc.update("""
                insert into balance_account_detail(org_id, account_id, settlement_id, action, money,
                    balance_after, memo, operator_id) values (?,?,?,?,?,?,?,?)
                """, orgId, accountId, settlementId, ACT_SETTLE_ENTER, money, after, null, operatorId);
        settlements.track(orgId, settlementId, "enter", "台账入账 " + money, operatorId);
    }

    /** 出纳现金腿付款:出纳账户原子扣减(余额不足拒)+ 归属账户懒开户同步(允许透支=预付)。 */
    @Transactional
    public void payCash(long orgId, long settlementId, long cashierAccountId, long money,
            String memo, long operatorId) {
        requirePositive(money);
        Map<String, Object> settlement = settlements.load(orgId, settlementId);
        settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
        Map<String, Object> cashier = load(orgId, cashierAccountId);
        requireType(cashier, TYPE_CASHIER);
        requireEnabled(cashier);
        int updated = jdbc.update("""
                update balance_account set real_money = real_money + ?, surplus = surplus - ?
                where id = ? and surplus >= ?
                """, money, money, cashierAccountId, money);
        if (updated == 0) {
            throw new BizException(CODE_ACCOUNT_INSUFFICIENT, msg("balance.account.insufficient"));
        }
        long after = ((Number) load(orgId, cashierAccountId).get("surplus")).longValue();
        jdbc.update("""
                insert into balance_account_detail(org_id, account_id, settlement_id, action, money,
                    balance_after, memo, operator_id) values (?,?,?,?,?,?,?,?)
                """, orgId, cashierAccountId, settlementId, ACT_PAY_CASH, money, after,
                BalanceSettlementService.blankToNull(memo), operatorId);
        // 归属结算账户懒开户同步(无守卫允许透支,修老 locateOrCreateAccount 先查后建并发双开)
        String owner = String.valueOf(settlement.get("supplier"));
        String ownerName = String.valueOf(settlement.get("supplier_name"));
        long settleAccountId = locateOrCreateSettleAccount(orgId, owner, ownerName, operatorId);
        jdbc.update("""
                update balance_account set real_money = real_money + ?, surplus = surplus - ?
                where id = ?
                """, money, money, settleAccountId);
        long afterSync = ((Number) load(orgId, settleAccountId).get("surplus")).longValue();
        jdbc.update("""
                insert into balance_account_detail(org_id, account_id, settlement_id, action, money,
                    balance_after, memo, operator_id) values (?,?,?,?,?,?,?,?)
                """, orgId, settleAccountId, settlementId, ACT_LAZY_SYNC, money, afterSync, null,
                operatorId);
        jdbc.update("update balance_settlement set paid = paid + ? where id = ? and status = ?",
                money, settlementId, BalanceSettlementService.ST_SETTLED);
        settlements.track(orgId, settlementId, "pay", "出纳付款 " + money, operatorId);
    }

    /** 付款标(单个/批量,全或无):0→1 条件更新,重复显式拒绝(修老可重复打标+null 静默 continue)。 */
    @Transactional
    public void markPaid(long orgId, List<Long> ids, long operatorId) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(CODE_PAYMARK_DUP, msg("balance.paymark.duplicated"));
        }
        for (Long id : ids) {
            if (id == null) {
                throw new BizException(CODE_PAYMARK_DUP, msg("balance.paymark.duplicated"));
            }
            Map<String, Object> settlement = settlements.load(orgId, id);
            settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
            int updated = jdbc.update("""
                    update balance_settlement set is_pay = 1 where id = ? and is_pay = 0
                    """, id);
            if (updated == 0) {
                throw new BizException(CODE_PAYMARK_DUP, msg("balance.paymark.duplicated"));
            }
            settlements.track(orgId, id, "payMark", "打付款标", operatorId);
        }
    }

    /** 完结(唯一定义,单向无逆向):已结算 + 余量清零 + 已付款。 */
    @Transactional
    public void finish(long orgId, long id, long operatorId) {
        Map<String, Object> settlement = settlements.load(orgId, id);
        settlements.requireStatus(settlement, BalanceSettlementService.ST_SETTLED);
        int updated = jdbc.update("""
                update balance_settlement set status = 4, finished_at = now()
                where id = ? and status = ? and surplus = 0 and is_pay = 1
                """, id, BalanceSettlementService.ST_SETTLED);
        if (updated == 0) {
            throw new BizException(CODE_FINISH_GUARD, msg("balance.finish.guard"));
        }
        settlements.track(orgId, id, "finish", "完结(余量清零且已付款)", operatorId);
    }

    // ==== 内部 ====

    /** 懒开户:insert ignore 兜并发,再按唯一键反查(修老先查后建竞态)。 */
    private long locateOrCreateSettleAccount(long orgId, String belonger, String belongerName,
            long operatorId) {
        jdbc.update("""
                insert ignore into balance_account(org_id, belonger, belonger_name, type, created_by)
                values (?,?,?,0,?)
                """, orgId, belonger == null ? "" : belonger,
                BalanceSettlementService.notBlank(belongerName) ? belongerName : "结算账户",
                operatorId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from balance_account where org_id = ? and belonger = ? and type = ?
                """, orgId, belonger == null ? "" : belonger, TYPE_SETTLE);
        if (rows.isEmpty()) {
            throw new BizException(CODE_ACCOUNT_NOT_FOUND, msg("balance.account.notFound"));
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from balance_account where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_ACCOUNT_NOT_FOUND, msg("balance.account.notFound"));
        }
        return rows.get(0);
    }

    private void requireType(Map<String, Object> account, int expected) {
        if (((Number) account.get("type")).intValue() != expected) {
            throw new BizException(CODE_TYPE_MISMATCH, msg("balance.account.type.mismatch"));
        }
    }

    private void requireEnabled(Map<String, Object> account) {
        if (((Number) account.get("status")).intValue() != 0) {
            throw new BizException(CODE_ACCOUNT_DISABLED, msg("balance.account.disabled"));
        }
    }

    private void requirePositive(long money) {
        if (money <= 0) {
            throw new BizException(CODE_MONEY_POSITIVE, msg("balance.money.positive"));
        }
    }

    String msg(String key) {
        return messages.getMessage(key, null, org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }
}
