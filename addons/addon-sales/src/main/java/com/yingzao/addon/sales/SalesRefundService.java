package com.yingzao.addon.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.PaymentApi;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.notification.NotificationService;
import com.yingzao.core.web.BizException;

/**
 * 退货线:唯一创建入口 = 退货审批批准(修老发起即改数据)——发起时原子预扣销售明细 num
 * (修老发起预扣驳回不回加的旁支),落退货单 3 审批中+内核轻量审批;批准生效(3→0)+
 * 主单轴 0/5→2 全退 / 3 倒单(修老"退货→作废"假设);驳回/撤回→2 无效+预扣回加。
 * 客户确认(验证码三重,与销售单确认同一体系)后按 rid 幂等生成退货结算负值行
 * (守卫主单已预结,修老 balance_status∈{0,4} 静默 return);全退且金额与款项一致时
 * 经 PaymentApi.submitRefund 提交退款(部分退/金额不符显式落人工,修老信任前端金额)。
 */
@Service
public class SalesRefundService {

    public static final int CODE_NOT_FOUND = 300016;
    public static final int CODE_NUM_OVER = 300017;
    public static final int CODE_SETTLE_DUP = 300018;
    public static final int CODE_NOT_PRESETTLED = 300019;
    public static final int CODE_APPROVAL_PENDING = 300025;
    public static final int CODE_REFUND_MISMATCH = 300026;
    public static final int CODE_ALREADY_CONFIRMED = 300012;

    public static final int TYPE_FULL_REFUND = 2;
    public static final int TYPE_COUNTERMAND = 3;

    // 退货单状态:3 审批中 / 0 初始(批准生效)/ 1 已退款 / 2 无效
    public static final int RS_APPROVING = 3;
    public static final int RS_INIT = 0;
    public static final int RS_REFUNDED = 1;
    public static final int RS_VOID = 2;

    public static final String BIZ_TYPE_REFUND = "saleRefund";

    public record RefundItemCmd(Long saleItemId, Long num) {
    }

    private final JdbcTemplate jdbc;
    private final SalesOrderService orders;
    private final ApprovalService approvals;
    private final PaymentApi payments;
    private final NotificationService notifications;

    public SalesRefundService(JdbcTemplate jdbc, SalesOrderService orders, ApprovalService approvals,
            PaymentApi payments, NotificationService notifications) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.approvals = approvals;
        this.payments = payments;
        this.notifications = notifications;
    }

    // ---- 发起(审批驱动) ----

    @Transactional
    public long initiate(long orgId, long saleId, int type, List<RefundItemCmd> items,
            Long assigneeId, String remark, long operatorId) {
        Map<String, Object> sale = orders.loadSale(orgId, saleId);
        int status = ((Number) sale.get("status")).intValue();
        if (status != SalesOrderService.ST_INIT && status != SalesOrderService.ST_FINISH) {
            throw new BizException(CODE_NOT_FOUND, orders.msg("sales.state.illegal"));
        }
        require(type == TYPE_FULL_REFUND || type == TYPE_COUNTERMAND, SalesOrderService.CODE_PARAM,
                "sales.param.invalid");
        require(items != null && !items.isEmpty(), SalesOrderService.CODE_EMPTY_ITEMS, "sales.items.empty");
        require(assigneeId != null, SalesOrderService.CODE_PARAM, "sales.param.invalid");
        Integer pending = jdbc.queryForObject("""
                select count(*) from sales_refunds
                where org_id = ? and sale_id = ? and status = ?
                """, Integer.class, orgId, saleId, RS_APPROVING);
        if (pending != null && pending > 0) {
            throw new BizException(CODE_APPROVAL_PENDING, orders.msg("sales.refund.pending"));
        }
        jdbc.update("""
                insert into sales_refunds(org_id, sale_id, order_id, type, status, is_affirm,
                    amount, remark, created_by, created_by_name)
                values (?,?,?,?,?,?,0,?,?,?)
                """, orgId, saleId, ((Number) sale.get("order_id")).longValue(), type, RS_APPROVING,
                0, SalesOrderService.blankToNull(remark), operatorId, orders.displayName(operatorId));
        long refundId = jdbc.queryForObject("select last_insert_id()", Long.class);
        long amount = 0;
        for (RefundItemCmd cmd : items) {
            require(cmd != null && cmd.saleItemId() != null && cmd.num() != null && cmd.num() > 0,
                    SalesOrderService.CODE_PARAM, "sales.param.invalid");
            Map<String, Object> item = orders.loadItem(orgId, cmd.saleItemId());
            if (((Number) item.get("sale_id")).longValue() != saleId) {
                throw new BizException(SalesOrderService.CODE_ITEM_MISMATCH, orders.msg("sales.item.mismatch"));
            }
            // 发起即原子预扣(可退余量 = 明细当前 num),驳回回加
            int deducted = jdbc.update("""
                    update sales_order_item set num = num - ?
                    where id = ? and org_id = ? and num >= ?
                    """, cmd.num(), cmd.saleItemId(), orgId, cmd.num());
            if (deducted == 0) {
                throw new BizException(CODE_NUM_OVER, orders.msg("sales.refund.numOver"));
            }
            long disPrice = ((Number) item.get("dis_price")).longValue();
            long lineTotal = (disPrice * cmd.num() + 500) / 1000;
            amount += lineTotal;
            jdbc.update("""
                    insert into sales_refunds_item(org_id, refund_id, sale_item_id, goods_name,
                        color_name, format_name, num, selling, total)
                    values (?,?,?,?,?,?,?,?,?)
                    """, orgId, refundId, cmd.saleItemId(), item.get("goods_name"),
                    item.get("color_name"), item.get("format_name"), cmd.num(),
                    item.get("selling"), lineTotal);
        }
        jdbc.update("update sales_refunds set amount = ? where id = ?", amount, refundId);
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(orgId, operatorId,
                orders.msgArgs("sales.refund.approvalTitle", sale.get("code"), yuan(amount)),
                SalesOrderService.BIZ_ADDON, BIZ_TYPE_REFUND, String.valueOf(refundId), assigneeId));
        jdbc.update("update sales_refunds set approval_id = ? where id = ?", approvalId, refundId);
        orders.track(orgId, saleId, "refund", "发起退货审批(类型 " + type + ",金额 " + yuan(amount) + ")",
                operatorId);
        return refundId;
    }

    // ---- 客户确认(幂等;生成退货结算负值行+全退自动提交退款) ----

    @Transactional
    public void sendVerifyCode(long orgId, long refundId, String phone, long operatorId) {
        load(orgId, refundId);
        require(SalesOrderService.notBlank(phone) && phone.trim().matches("\\d{6,20}"),
                SalesOrderService.CODE_CONFIRM_PARAMS, "sales.confirm.params");
        Integer recent = jdbc.queryForObject("""
                select count(*) from sales_verify_code
                where org_id = ? and biz_type = 'refund' and biz_id = ? and phone = ?
                  and created_at > date_sub(now(), interval 1 minute)
                """, Integer.class, orgId, refundId, phone.trim());
        if (recent != null && recent > 0) {
            throw new BizException(SalesOrderService.CODE_VERIFY_FREQUENT, orders.msg("sales.verify.frequent"));
        }
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        jdbc.update("""
                insert into sales_verify_code(org_id, biz_type, biz_id, phone, code, expire_at)
                values (?,?,?,?,?, date_add(now(), interval 10 minute))
                """, orgId, "refund", refundId, phone.trim(), code);
        notifications.send(new NotificationService.SendCmd(orgId, operatorId,
                orders.msg("sales.verify.notifyTitle"), orders.msg("sales.verify.notifyBody") + code,
                SalesOrderService.BIZ_ADDON, "verifyCode", String.valueOf(refundId)));
    }

    @Transactional
    public void confirm(long orgId, long refundId, String phone, String code) {
        require(SalesOrderService.notBlank(phone) && SalesOrderService.notBlank(code),
                SalesOrderService.CODE_CONFIRM_PARAMS, "sales.confirm.params");
        Map<String, Object> refund = load(orgId, refundId);
        require(((Number) refund.get("status")).intValue() == RS_INIT, SalesOrderService.CODE_BAD_STATE,
                "sales.state.illegal");
        require(((Number) refund.get("is_affirm")).intValue() == 0, CODE_ALREADY_CONFIRMED,
                "sales.confirm.duplicated");
        int updated = jdbc.update("""
                update sales_verify_code set used_at = now()
                where org_id = ? and biz_type = 'refund' and biz_id = ? and phone = ? and code = ?
                  and used_at is null and expire_at > now()
                """, orgId, refundId, phone.trim(), code.trim());
        require(updated > 0, SalesOrderService.CODE_VERIFY_CODE, "sales.verify.invalid");
        jdbc.update("""
                update sales_refunds set is_affirm = 1, affirmed_at = now()
                where id = ? and is_affirm = 0
                """, refundId);
        generateRefundSettlement(orgId, refund, refundId);
        if (((Number) refund.get("type")).intValue() == TYPE_FULL_REFUND) {
            submitRefundForFull(orgId, refund, refundId);
        }
    }

    /** 退货结算负值行:守卫主单已预结(显式报错,修老静默 return);rid 幂等(uk+先查)。 */
    private void generateRefundSettlement(long orgId, Map<String, Object> refund, long refundId) {
        long saleId = ((Number) refund.get("sale_id")).longValue();
        Map<String, Object> sale = orders.loadSale(orgId, saleId);
        int bs = ((Number) sale.get("balance_status")).intValue();
        if (bs == SalesOrderService.BS_NONE || bs == 4) {
            throw new BizException(CODE_NOT_PRESETTLED, orders.msg("sales.refund.notPresettled"));
        }
        int type = ((Number) refund.get("type")).intValue();
        List<Map<String, Object>> groups = jdbc.queryForList("""
                select i.brand_id, i.brand_name, sum(r.total) as amount
                from sales_refunds_item r join sales_order_item i on i.id = r.sale_item_id
                where r.org_id = ? and r.refund_id = ?
                group by i.brand_id, i.brand_name
                """, orgId, refundId);
        for (Map<String, Object> g : groups) {
            long brandId = ((Number) g.get("brand_id")).longValue();
            String brandName = (String) g.get("brand_name");
            long amount = ((Number) g.get("amount")).longValue();
            Integer dup = jdbc.queryForObject("""
                    select count(*) from sales_settlement
                    where org_id = ? and sale_id = ? and brand_id = ? and type = ? and rid = ?
                    """, Integer.class, orgId, saleId, brandId, SalesSettlementService.TYPE_REFUND_SETTLE,
                    refundId);
            if (dup != null && dup > 0) {
                throw new BizException(CODE_SETTLE_DUP, orders.msg("sales.refund.settleDup"));
            }
            jdbc.update("""
                    insert into sales_settlement(org_id, sale_id, brand_id, brand_name, type, onetype,
                        rid, total, balance, vbalance, pbalance, difference, status, created_by)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,0,?)
                    """, orgId, saleId, brandId, brandName == null ? "" : brandName,
                    SalesSettlementService.TYPE_REFUND_SETTLE, type, refundId, -amount, -amount,
                    -amount, -amount, 0, 0L);
        }
        orders.track(orgId, saleId, "refundSettle", "客户确认退货,生成退货结算行", 0L);
    }

    /** 全退自动提交退款:金额与全款款项一致才走;部分退/金额不符/提交失败一律显式落人工
     * (轨迹留痕,客户确认本身不被阻塞——修老信任前端金额,PRD 口径)。 */
    private void submitRefundForFull(long orgId, Map<String, Object> refund, long refundId) {
        long saleId = ((Number) refund.get("sale_id")).longValue();
        long amount = ((Number) refund.get("amount")).longValue();
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, SalesOrderService.BIZ_ADDON,
                SalesOrderService.BIZ_TYPE_ORDER, String.valueOf(saleId))) {
            if (bill.direction() != 0 || bill.amount() != amount
                    || (bill.status() != 1 && bill.status() != 2)) {
                continue;
            }
            try {
                payments.submitRefund(new PaymentApi.RefundCmd(orgId, bill.billId(),
                        ((Number) refund.get("created_by")).longValue(),
                        ((Number) refund.get("created_by")).longValue(),
                        orders.msgArgs("sales.refund.refundReason", refundId)));
            } catch (BizException e) {
                if (e.getCode() != 270012) { // 进行中退款审批 = 幂等吞;其余失败落人工
                    orders.track(orgId, saleId, "refundManual", "自动退款提交失败,请人工处理", 0L);
                }
            }
            return;
        }
        orders.track(orgId, saleId, "refundManual",
                "退款金额 " + yuan(amount) + " 与款项不符,请人工处理", 0L);
    }

    // ---- 审批办结回写(RefundApprovalListener 调入;AFTER_COMMIT+REQUIRES_NEW) ----

    @Transactional
    public void onApprovalFinished(long orgId, long refundId, boolean approved, long operatorId) {
        Map<String, Object> refund = load(orgId, refundId);
        long saleId = ((Number) refund.get("sale_id")).longValue();
        int type = ((Number) refund.get("type")).intValue();
        if (approved) {
            int updated = jdbc.update(
                    "update sales_refunds set status = 0 where id = ? and status = ?", refundId, RS_APPROVING);
            if (updated == 0) {
                return; // 重放守卫
            }
            Map<String, Object> sale = orders.loadSale(orgId, saleId);
            // 主单轴 2 全退/3 倒单:仅当主项明细全部退光(SUM(num)==0,发起即预扣)才置;
            // 部分退不动主单轴(单据仍可完结,负向结算照生成——修老"退货即作废"歧义)
            Long remain = jdbc.queryForObject("""
                    select coalesce(sum(num), 0) from sales_order_item
                    where org_id = ? and sale_id = ? and package_id = 0
                    """, Long.class, orgId, saleId);
            boolean drained = remain != null && remain == 0;
            if (drained) {
                int from = ((Number) sale.get("status")).intValue();
                jdbc.update("update sales_order set status = ? where id = ? and status in (0, 5)",
                        type, saleId);
                orders.publishStatus(orgId, sale, saleId, from, type,
                        type == TYPE_FULL_REFUND ? com.yingzao.addons.api.event.SalesOrderStatusChangedEvent.ACTION_FULL_REFUND
                                : com.yingzao.addons.api.event.SalesOrderStatusChangedEvent.ACTION_COUNTERMAND,
                        operatorId);
            }
            orders.track(orgId, saleId, type == TYPE_FULL_REFUND ? "fullRefund" : "countermand",
                    drained ? "退货审批通过,主单轴置 " + type : "退货审批通过(部分退,主单轴不动)", operatorId);
        } else {
            int updated = jdbc.update(
                    "update sales_refunds set status = ? where id = ? and status = ?", RS_VOID, refundId,
                    RS_APPROVING);
            if (updated == 0) {
                return;
            }
            List<Map<String, Object>> items = jdbc.queryForList(
                    "select sale_item_id, num from sales_refunds_item where org_id = ? and refund_id = ?",
                    orgId, refundId);
            for (Map<String, Object> i : items) {
                jdbc.update("update sales_order_item set num = num + ? where id = ?",
                        i.get("num"), i.get("sale_item_id"));
            }
            orders.track(orgId, saleId, "refundReject", "退货审批驳回,预扣回加", operatorId);
        }
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Long saleId, Integer status, Integer type,
            int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (saleId != null) {
            where.append(" and sale_id = ?");
            args.add(saleId);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from sales_refunds" + w, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, sale_id, order_id, type, status, approval_id, is_affirm, amount, remark,
                       created_by_name, created_at
                from sales_refunds""" + w + " order by id desc limit ? offset ?",
                SalesOrderService.appendArgs(args, safeSize, (safePage - 1) * safeSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long refundId) {
        Map<String, Object> refund = load(orgId, refundId);
        refund.put("items", jdbc.queryForList("""
                select id, sale_item_id, goods_name, color_name, format_name, num, selling, total
                from sales_refunds_item where org_id = ? and refund_id = ? order by id
                """, orgId, refundId));
        return refund;
    }

    public Map<String, Object> load(long orgId, long refundId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_refunds where id = ? and org_id = ?", refundId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, orders.msg("sales.refund.notFound"));
        }
        return rows.get(0);
    }

    private String yuan(long li) {
        return li % 1000 == 0 ? String.valueOf(li / 1000) + "元"
                : String.format("%.2f元", li / 1000.0);
    }

    private void require(boolean condition, int code, String key) {
        if (!condition) {
            throw new BizException(code, orders.msg(key));
        }
    }
}
