package com.yingzao.addon.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.QuotationApi;
import com.yingzao.addons.api.event.QuotationAddReduceConfirmedEvent;
import com.yingzao.addons.api.event.QuotationAddReduceSubmittedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 增减差量单(合同后变更,详细设计 docs/prd/addon-quotation-addreduce.md;231000 段)。
 *
 * 状态机:0 草稿 → 1 已提交(单事务聚合+PaymentApi.createBill typeCode=ar_diff 回写 dp_id+
 * 发 QuotationAddReduceSubmittedEvent)→ PaymentConfirmedEvent 回程置 2 已付款 → confirm 2→3 已确认终态;
 * 草稿物理删;已提交未付款可撤(withdrawn_at 归档回 0+cancelBill);已付款不可撤(230152)。
 *
 * 守卫:开单/提交前置=装修单签约(230154)+默认报价锁定且审核完成(230156);
 * Σ差量=0 拒(230150);差量款确认事件按 bizAddon/bizType/bizId 匹配;重复确认幂等回显。
 *
 * 差量金额全厘服务端算(add_price = num × unit_price / 1000);净差量 ≤0 拒(230157,
 * 净退款方向走 V2.1 退款审批流);差量确认终态并入「确认价格合计」并消费方留事件口。
 */
@Service
public class QuotationAddReduceService {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_SUBMITTED = 1;
    public static final int STATUS_PAID = 2;
    public static final int STATUS_CONFIRMED = 3;

    public static final int CODE_NOT_FOUND = 230100;
    public static final int CODE_PARAM = 230101;
    public static final int CODE_ORDER_NOT_SIGNED = 230102;
    public static final int CODE_NO_DEFAULT_QUOTATION = 230103;
    public static final int CODE_EMPTY_ITEMS = 230104;
    public static final int CODE_ZERO_TOTAL = 230105;
    public static final int CODE_ITEM_NUM = 230106;
    public static final int CODE_STATE = 230107;
    public static final int CODE_PAID_NOT_CANCELLABLE = 230108;
    public static final int CODE_ALREADY_CONFIRMED = 230109;
    public static final int CODE_BILL_MISSING = 230110;

    private static final String BIZ_ADDON = "quotation";
    private static final String BIZ_TYPE = "ar_diff";
    private static final String TYPE_CODE = "ar_diff";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final PaymentApi payments;
    private final DecorationApi decorations;
    private final QuotationApi quotations;
    public QuotationAddReduceService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            PaymentApi payments, DecorationApi decorations, QuotationApi quotations) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.payments = payments;
        this.decorations = decorations;
        this.quotations = quotations;
    }

    public record ArItemCmd(Integer itemType, Long refQiid, Long goodsId, String goodsName,
            String unit, Long unitPrice, Long num) {
    }

    // ---- 查询 ----

    public Map<String, Object> get(long orgId, long arId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, order_id, order_no, ar_no, quotation_id, status,
                       total_add, total_sub, dp_id, withdrawn_at, confirm_time, remark,
                       created_by, created_at, updated_at
                from quotation_add_reduce where id = ? and org_id = ?
                """, arId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("ar.notfound"));
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("items", jdbc.queryForList("""
                select id, item_type, ref_qiid, goods_id, goods_name, unit, unit_price, num,
                       add_price, created_at
                from quotation_add_reduce_item where ar_id = ? order by id
                """, arId));
        return out;
    }

    public Map<String, Object> page(long orgId, Long orderId, Integer status, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
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
        Long total = jdbc.queryForObject("select count(*) from quotation_add_reduce" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, order_no, ar_no, quotation_id, status, total_add, total_sub,
                       dp_id, confirm_time, remark, created_at
                from quotation_add_reduce""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 草稿 ----

    /** 开差量单草稿:前置=装修单签约(230102);状态 0。 */
    @Transactional
    public long open(long orgId, Long orderId, List<ArItemCmd> items, String remark, long operatorId) {
        require(orderId != null, CODE_PARAM, "ar.param");
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_ORDER_NOT_SIGNED, msg("ar.order.notSigned")));
        // 合同后变更前置:装修单已签约(合同线 3)
        require(order.status() == 3, CODE_ORDER_NOT_SIGNED, "ar.order.notSigned");
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "ar.items.empty");
        String orderNo = order.orderNo();
        String arNo = nextArNo(orgId);
        try {
            jdbc.update("""
                    insert into quotation_add_reduce(org_id, order_id, order_no, ar_no, quotation_id,
                        status, remark, created_by)
                    select ?, ?, ?, ?, coalesce((select id from quotation_quotation
                        where org_id = ? and order_id = ? and is_default = 1 limit 1), 0),
                        0, ?, ?
                    """, orgId, orderId, orderNo, arNo, orgId, orderId, blankToNull(remark), operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PARAM, msg("ar.param"));
        }
        long arId = jdbc.queryForObject("select last_insert_id()", Long.class);
        replaceItems(orgId, arId, items);
        recomputeTotals(orgId, arId);
        return arId;
    }

    /** 改草稿(全量替换明细+重算;仅状态 0)。 */
    @Transactional
    public void updateDraft(long orgId, long arId, List<ArItemCmd> items, String remark, long operatorId) {
        Map<String, Object> head = loadHead(orgId, arId);
        require(((Number) head.get("status")).intValue() == STATUS_DRAFT, CODE_STATE, "ar.state.draft");
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "ar.items.empty");
        jdbc.update("delete from quotation_add_reduce_item where ar_id = ?", arId);
        replaceItems(orgId, arId, items);
        jdbc.update("update quotation_add_reduce set remark = ? where id = ?",
                blankToNull(remark), arId);
        recomputeTotals(orgId, arId);
    }

    /** 删草稿(物理删,仅状态 0)。 */
    @Transactional
    public void deleteDraft(long orgId, long arId) {
        Map<String, Object> head = loadHead(orgId, arId);
        require(((Number) head.get("status")).intValue() == STATUS_DRAFT, CODE_STATE, "ar.state.draft");
        jdbc.update("delete from quotation_add_reduce_item where ar_id = ?", arId);
        jdbc.update("delete from quotation_add_reduce where id = ?", arId);
    }

    // ---- 提交/撤回/确认 ----

    /** 提交(单事务):重算→Σ≠0→默认报价锁定+审核完成→生成差量款项单→回写 dp_id→发事件。 */
    @Transactional
    public long submit(long orgId, long arId, long operatorId) {
        Map<String, Object> head = loadHead(orgId, arId);
        require(((Number) head.get("status")).intValue() == STATUS_DRAFT, CODE_STATE, "ar.state.draft");
        long orderId = ((Number) head.get("order_id")).longValue();
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_ORDER_NOT_SIGNED, msg("ar.order.notSigned")));
        require(order.status() == 3, CODE_ORDER_NOT_SIGNED, "ar.order.notSigned");
        requireDefaultQuotationReady(orgId, orderId);
        recomputeTotals(orgId, arId);
        Map<String, Object> fresh = loadHead(orgId, arId);
        long totalAdd = ((Number) fresh.get("total_add")).longValue();
        long totalSub = ((Number) fresh.get("total_sub")).longValue();
        require(totalAdd - totalSub != 0, CODE_ZERO_TOTAL, "ar.zeroTotal");
        require(totalAdd - totalSub > 0, CODE_ZERO_TOTAL, "ar.negativeTotal");

        long quotationId = ((Number) fresh.get("quotation_id")).longValue();
        // ar_diff 类型懒预置(照 AdvanceService 科目懒预置惯例;组织自建类型,协议键全局约定)
        Integer typeExists = jdbc.queryForObject(
                "select count(*) from payment_bill_type where org_id = ? and code = ?",
                Integer.class, orgId, TYPE_CODE);
        if (typeExists == null || typeExists == 0) {
            jdbc.update("insert into payment_bill_type(org_id, code, name, sort) values (?,?,?,90)",
                    orgId, TYPE_CODE, "增减差量款");
        }
        long dpId = payments.createBill(new PaymentApi.CreateBillCmd(orgId, BIZ_ADDON, BIZ_TYPE,
                String.valueOf(arId), TYPE_CODE, totalAdd - totalSub,
                "增减差量款 " + (String) fresh.get("ar_no")));
        jdbc.update("update quotation_add_reduce set status = ?, dp_id = ? where id = ?",
                STATUS_SUBMITTED, dpId, arId);
        eventBus.publish(new QuotationAddReduceSubmittedEvent(arId, orgId, orderId, dpId,
                totalAdd, totalSub, blankToNull((String) fresh.get("remark"))),
                "ar-submitted-" + arId);
        return dpId;
    }

    /** 撤回提交(仅已提交未收款):作废差量款项单+归档回草稿。 */
    @Transactional
    public void cancelSubmit(long orgId, long arId, long operatorId) {
        Map<String, Object> head = loadHead(orgId, arId);
        require(((Number) head.get("status")).intValue() == STATUS_SUBMITTED, CODE_STATE, "ar.state.submitted");
        Long dpId = head.get("dp_id") == null ? null : ((Number) head.get("dp_id")).longValue();
        require(dpId != null, CODE_BILL_MISSING, "ar.param");
        try {
            payments.cancelBill(orgId, dpId);
        } catch (BizException e) {
            // 款项已收款/已确认:不可撤
            throw new BizException(CODE_PAID_NOT_CANCELLABLE, msg("ar.paid"));
        }
        jdbc.update("""
                update quotation_add_reduce set status = 0, withdrawn_at = now(), dp_id = null
                where id = ?
                """, arId);
    }

    /** 财务确认(2→3 终态;差量金额并入确认价格合计)。 */
    @Transactional
    public void confirm(long orgId, long arId, long operatorId) {
        Map<String, Object> head = loadHead(orgId, arId);
        int status = ((Number) head.get("status")).intValue();
        if (status == STATUS_CONFIRMED) {
            return; // 重复确认幂等回显
        }
        require(status == STATUS_PAID, CODE_ALREADY_CONFIRMED, "ar.state.paid");
        int updated = jdbc.update("""
                update quotation_add_reduce set status = ?, confirm_time = now()
                where id = ? and status = ?
                """, STATUS_CONFIRMED, arId, STATUS_PAID);
        if (updated == 0) {
            throw new BizException(CODE_STATE, msg("ar.state.submitted"));
        }
        long orderId = ((Number) head.get("order_id")).longValue();
        long totalAdd = ((Number) head.get("total_add")).longValue();
        long totalSub = ((Number) head.get("total_sub")).longValue();
        eventBus.publish(new QuotationAddReduceConfirmedEvent(arId, orgId, orderId,
                totalAdd - totalSub), "ar-confirmed-" + arId);
    }

    /** PaymentConfirmedEvent 回程(消费器入口):差量款项确认 → 差量单 1→2。按 biz 三元组匹配。 */
    @Transactional
    public void onPaymentConfirmed(long orgId, String bizAddon, String bizType, String bizId) {
        if (!BIZ_ADDON.equals(bizAddon) || !BIZ_TYPE.equals(bizType)) {
            return;
        }
        long arId;
        try {
            arId = Long.parseLong(bizId);
        } catch (NumberFormatException e) {
            return;
        }
        jdbc.update("""
                update quotation_add_reduce set status = ? where id = ? and org_id = ? and status = ?
                """, STATUS_PAID, arId, orgId, STATUS_SUBMITTED);
    }

    // ---- 内部 ----

    /** 默认报价就绪(锁定+审核完成);默认报价不存在同样拒(差量必须锚定已确认价格线)。 */
    private void requireDefaultQuotationReady(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, attstatus from quotation_quotation
                where org_id = ? and order_id = ? and is_default = 1 limit 1
                """, orgId, orderId);
        require(!rows.isEmpty(), CODE_NO_DEFAULT_QUOTATION, "ar.noQuotation");
        int status = ((Number) rows.get(0).get("status")).intValue();
        int attstatus = ((Number) rows.get(0).get("attstatus")).intValue();
        require(status == 1 && attstatus == 3, CODE_NO_DEFAULT_QUOTATION, "ar.quotation.notReady");
    }

    private void replaceItems(long orgId, long arId, List<ArItemCmd> items) {
        int sort = 0;
        for (ArItemCmd cmd : items) {
            sort++;
            require(cmd != null && cmd.itemType() != null && cmd.itemType() >= 0 && cmd.itemType() <= 2,
                    CODE_PARAM, "ar.param");
            require(cmd.unitPrice() != null && cmd.num() != null && cmd.num() != 0,
                    CODE_ITEM_NUM, "ar.itemNum");
            long addPrice = BigDecimal.valueOf(cmd.num())
                    .multiply(BigDecimal.valueOf(cmd.unitPrice()))
                    .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP)
                    .longValue();
            jdbc.update("""
                    insert into quotation_add_reduce_item(org_id, ar_id, item_type, ref_qiid,
                        goods_id, goods_name, unit, unit_price, num, add_price)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """, orgId, arId, cmd.itemType(), cmd.refQiid(), cmd.goodsId(),
                    requireName(cmd.goodsName()), blankToNull(cmd.unit()), cmd.unitPrice(),
                    cmd.num(), addPrice);
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(CODE_PARAM, msg("ar.param"));
        }
        return name.trim();
    }

    /** 重算主表聚合(服务端口径:Σ正=total_add,Σ负绝对值=total_sub)。 */
    private void recomputeTotals(long orgId, long arId) {
        jdbc.update("""
                update quotation_add_reduce a set
                    total_add = coalesce((select sum(case when add_price > 0 then add_price else 0 end)
                        from quotation_add_reduce_item where ar_id = a.id), 0),
                    total_sub = coalesce((select sum(-case when add_price < 0 then add_price else 0 end)
                        from quotation_add_reduce_item where ar_id = a.id), 0)
                where a.id = ?
                """, arId);
    }

    /** AR 单号:AR+yyMMdd+4 位流水(计数器 last_insert_id 原子自增;uk 撞号由调用方重试语义兜底)。 */
    private String nextArNo(long orgId) {
        jdbc.update("insert into quotation_counter(name, seq) values ('AR', last_insert_id(1))"
                + " on duplicate key update seq = last_insert_id(seq + 1)");
        long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
        return "AR" + DateTimeFormatter.ofPattern("yyMMdd")
                .format(LocalDateTime.now()) + String.format("%04d", seq);
    }

    private Map<String, Object> loadHead(long orgId, long arId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, order_id, order_no, ar_no, quotation_id, status, total_add,
                       total_sub, dp_id, withdrawn_at, confirm_time, remark
                from quotation_add_reduce where id = ? and org_id = ?
                """, arId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("ar.notfound"));
        }
        return rows.get(0);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }
}
