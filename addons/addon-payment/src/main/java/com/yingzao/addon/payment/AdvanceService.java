package com.yingzao.addon.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.AdvancePaymentChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 预付款科目化台账:组织自定义科目字典(首次访问懒预置 合同/工程/主材,替代老正林列级硬编码)
 * + 科目×业务归属一户一行的余额表 + 流水表分离。
 * 对老缺陷的显式修正:
 *   所有余额变动走原子条件 SQL(扣减 balance>=amount——复活老 AdvancePaymentDaoImpl 死代码守卫;
 *   老三条路径写同一余额守卫强度不一/addPayment 写流水不动余额快照脱节);
 *   金额入参恒正强制 >0(老负数绕过余额检查反向加钱);balance_after 快照使任意时点 余额==Σ流水 可对账。
 */
@Service
public class AdvanceService {

    public static final int CODE_SUBJECT_NOT_FOUND = 270014;
    public static final int CODE_SUBJECT_DUP = 270015;
    public static final int CODE_ACCOUNT_NOT_FOUND = 270016;
    public static final int CODE_INSUFFICIENT = 270017;
    public static final int CODE_AMOUNT = 270018;
    public static final int CODE_INVALID_PARAM = BillTypeService.CODE_INVALID_PARAM;   // 270001

    public static final int FLOW_CHARGE = 1;
    public static final int FLOW_DEDUCT = 2;
    public static final int FLOW_WITHDRAW = 3;
    public static final int FLOW_RETURN = 4;

    /** 懒预置三科目(替代老合同/工程/主材/正林四列)。 */
    private static final String[][] DEFAULT_SUBJECTS = {
            {"contract", "合同预付款"}, {"engineering", "工程预付款"}, {"material", "主材预付款"}};

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;

    public AdvanceService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
    }

    // ---- 科目字典 ----

    public List<Map<String, Object>> subjects(long orgId, boolean includeDisabled) {
        ensureDefaults(orgId);
        return jdbc.queryForList("""
                select id, code, name, status, created_at, updated_at
                from payment_advance_subject where org_id = ? %s order by id
                """.formatted(includeDisabled ? "" : "and status = 0"), orgId);
    }

    @Transactional
    public long createSubject(long orgId, String code, String name) {
        require(code != null && code.matches("[a-z][a-z0-9_]{1,49}") && name != null && !name.isBlank(),
                CODE_INVALID_PARAM, "payment.param.invalid");
        try {
            jdbc.update("insert into payment_advance_subject(org_id, code, name) values (?,?,?)",
                    orgId, code, name.trim());
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_SUBJECT_DUP, msg("payment.advance.subjectDup"));
        }
        return jdbc.queryForObject(
                "select id from payment_advance_subject where org_id = ? and code = ?", Long.class, orgId, code);
    }

    @Transactional
    public void updateSubjectStatus(long orgId, long subjectId, int status) {
        require(status == BillTypeService.STATUS_ENABLED || status == BillTypeService.STATUS_DISABLED,
                CODE_INVALID_PARAM, "payment.param.invalid");
        int updated = jdbc.update(
                "update payment_advance_subject set status = ? where id = ? and org_id = ?",
                status, subjectId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_SUBJECT_NOT_FOUND, msg("payment.advance.subjectNotFound"));
        }
    }

    // ---- 账户与流水 ----

    public List<Map<String, Object>> accounts(long orgId, Long subjectId, String refType, String refId) {
        StringBuilder where = new StringBuilder(" where a.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (subjectId != null) {
            where.append(" and a.subject_id = ?");
            args.add(subjectId);
        }
        if (refType != null && !refType.isBlank()) {
            where.append(" and a.ref_type = ?");
            args.add(refType.trim());
        }
        if (refId != null && !refId.isBlank()) {
            where.append(" and a.ref_id = ?");
            args.add(refId.trim());
        }
        return jdbc.queryForList("""
                select a.id, a.org_id, a.subject_id, s.code as subject_code, s.name as subject_name,
                       a.ref_type, a.ref_id, a.balance, a.status, a.created_at, a.updated_at
                from payment_advance_account a
                join payment_advance_subject s on s.id = a.subject_id
                """ + where + " order by a.id desc", args.toArray());
    }

    public Map<String, Object> flows(long orgId, long accountId, int page, int size) {
        Integer count = jdbc.queryForObject(
                "select count(*) from payment_advance_account where id = ? and org_id = ?",
                Integer.class, accountId, orgId);
        if (count == null || count == 0) {
            throw new BizException(CODE_ACCOUNT_NOT_FOUND, msg("payment.advance.accountNotFound"));
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject(
                "select count(*) from payment_advance_flow where account_id = ?", Long.class, accountId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, flow_type, amount, balance_after, biz_addon, biz_type, biz_id,
                       operator_id, operator_name, remark, created_at
                from payment_advance_flow where account_id = ? order by id desc limit ? offset ?
                """, accountId, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    /**
     * 余额变动唯一入口:流水与余额同事务,扣减走原子条件 SQL,发 AdvancePaymentChangedEvent(总线)。
     *
     * @param flowType 1 收入 / 2 划扣 / 3 提现 / 4 退回
     */
    @Transactional
    public long move(long orgId, String subjectCode, String refType, String refId, long amount, int flowType,
            String bizAddon, String bizType, String bizId, Long operatorId, String remark) {
        require(subjectCode != null && !subjectCode.isBlank() && refType != null && !refType.isBlank()
                && refId != null && !refId.isBlank(), CODE_INVALID_PARAM, "payment.param.invalid");
        require(amount > 0, CODE_AMOUNT, "payment.advance.amount");
        require(flowType >= FLOW_CHARGE && flowType <= FLOW_RETURN, CODE_INVALID_PARAM, "payment.param.invalid");
        List<Map<String, Object>> subjects = jdbc.queryForList("""
                select id from payment_advance_subject
                where org_id = ? and code = ? and status = 0
                """, orgId, subjectCode);
        if (subjects.isEmpty()) {
            throw new BizException(CODE_SUBJECT_NOT_FOUND, msg("payment.advance.subjectNotFound"));
        }
        long subjectId = ((Number) subjects.get(0).get("id")).longValue();
        long accountId = locateOrCreateAccount(orgId, subjectId, refType, refId);
        if (flowType == FLOW_DEDUCT || flowType == FLOW_WITHDRAW) {
            // 原子条件扣减:余额不足 0 行命中(两并发扣减只成功一次,不超支)
            int updated = jdbc.update(
                    "update payment_advance_account set balance = balance - ? where id = ? and balance >= ?",
                    amount, accountId, amount);
            if (updated == 0) {
                throw new BizException(CODE_INSUFFICIENT, msg("payment.advance.insufficient"));
            }
        } else {
            jdbc.update("update payment_advance_account set balance = balance + ? where id = ?",
                    amount, accountId);
        }
        long balanceAfter = jdbc.queryForObject(
                "select balance from payment_advance_account where id = ?", Long.class, accountId);
        String operatorName = operatorId == null ? null : displayName(operatorId);
        jdbc.update("""
                insert into payment_advance_flow(org_id, account_id, subject_id, flow_type, amount,
                    balance_after, biz_addon, biz_type, biz_id, operator_id, operator_name, remark)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, accountId, subjectId, flowType, amount, balanceAfter,
                blankToNull(bizAddon), blankToNull(bizType), blankToNull(bizId),
                operatorId, operatorName, blankToNull(remark));
        long flowId = jdbc.queryForObject("select last_insert_id()", Long.class);
        eventBus.publish(new AdvancePaymentChangedEvent(accountId, orgId, subjectCode, refType, refId,
                flowType, amount, balanceAfter, blankToNull(bizAddon), blankToNull(bizType), blankToNull(bizId)),
                "advance-flow:" + flowId);
        return flowId;
    }

    /** 余额查询:科目或账户不存在 = 未发生过 = 0(PaymentApi.advanceBalance 契约)。 */
    public long balance(long orgId, String subjectCode, String refType, String refId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.balance from payment_advance_account a
                join payment_advance_subject s on s.id = a.subject_id
                where a.org_id = ? and s.code = ? and a.ref_type = ? and a.ref_id = ?
                """, orgId, subjectCode, refType, refId);
        return rows.isEmpty() ? 0L : ((Number) rows.get(0).get("balance")).longValue();
    }

    // ---- 内部 ----

    private long locateOrCreateAccount(long orgId, long subjectId, String refType, String refId) {
        List<Long> existing = jdbc.queryForList("""
                select id from payment_advance_account
                where org_id = ? and subject_id = ? and ref_type = ? and ref_id = ?
                """, Long.class, orgId, subjectId, refType, refId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        try {
            jdbc.update("""
                    insert into payment_advance_account(org_id, subject_id, ref_type, ref_id)
                    values (?,?,?,?)
                    """, orgId, subjectId, refType, refId);
        } catch (DuplicateKeyException e) {
            // 并发首建:另一事务已建户,取它的
        }
        return jdbc.queryForObject("""
                select id from payment_advance_account
                where org_id = ? and subject_id = ? and ref_type = ? and ref_id = ?
                """, Long.class, orgId, subjectId, refType, refId);
    }

    private void ensureDefaults(long orgId) {
        for (String[] subject : DEFAULT_SUBJECTS) {
            jdbc.update("""
                    insert into payment_advance_subject(org_id, code, name)
                    select ?, ?, ? from dual
                    where not exists (select 1 from payment_advance_subject where org_id = ? and code = ?)
                    """, orgId, subject[0], subject[1], orgId, subject[0]);
        }
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }
}
