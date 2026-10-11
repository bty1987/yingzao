package com.yingzao.addon.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.addons.api.event.PaymentConfirmedEvent;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 款项单主链:创建(事件/API 两入口)→ 收款(守卫三件套 + 顺序门控 + 渠道合计平衡)→ 确认(终态)。
 * 对老缺陷的显式修正:
 *   金额/渠道金额恒正(老 collect 负数 recharge 先加后 abs 反转余额);先验后写(老批收先 save 收据后校验);
 *   顺序门控查全组前置款(老只看 sort-1 且 sort=0 隐蔽成本线不迁);状态迁移一律条件 UPDATE(老读-判-写无并发防护);
 *   退款统一走内核轻量审批(老直接退/审批退/设计费跳审批三轨并存)。
 * 事件经内核事件总线(outbox 三件套:消费确认+重试+死信),业务回写归各插件订阅,本域零业务知识。
 */
@Service
public class BillService {

    public static final int CODE_INVALID_PARAM = BillTypeService.CODE_INVALID_PARAM;   // 270001
    public static final int CODE_BILL_NOT_FOUND = 270002;
    public static final int CODE_BILL_BAD_STATE = 270003;
    public static final int CODE_BILL_DUP = 270006;
    public static final int CODE_CHANNEL_SUM = 270007;
    public static final int CODE_CHANNEL_AMOUNT = 270008;
    public static final int CODE_CHANNEL_INVALID = 270009;
    public static final int CODE_PREV_NOT_COLLECTED = 270010;
    public static final int CODE_REFUND_PENDING = 270012;

    public static final int DIRECTION_INCOME = 0;
    public static final int DIRECTION_REFUND = 1;

    public static final int STATUS_UNPAID = 0;
    public static final int STATUS_COLLECTED = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_REFUNDED = 3;
    public static final int STATUS_CANCELED = 4;

    /** 渠道白名单(V1 方式记录;渠道适配插件 V2)。 */
    public static final Set<String> CHANNELS = Set.of("cash", "bank", "scan", "other");

    public static final String BIZ_ADDON = "payment";
    public static final String BIZ_TYPE = "bill";

    private record BillRow(long id, long orgId, String bizAddon, String bizType, String bizId,
                           String typeCode, String typeName, int direction, Long originBillId,
                           Integer originStatusSnapshot, long amount, int sort, int status,
                           Long refundApprovalId) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final ApprovalService approvals;

    public BillService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus, ApprovalService approvals) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.approvals = approvals;
    }

    // ---- 创建(REST 与 PaymentApi 共用;金额恒正) ----

    @Transactional
    public long createBill(long orgId, String bizAddon, String bizType, String bizId, String typeCode,
            long amount, String remark, Long createdBy) {
        require(bizAddon != null && !bizAddon.isBlank() && bizType != null && !bizType.isBlank()
                && bizId != null && !bizId.isBlank() && typeCode != null && !typeCode.isBlank(),
                CODE_INVALID_PARAM, "payment.param.invalid");
        require(amount > 0, CODE_INVALID_PARAM, "payment.param.invalid");
        List<Map<String, Object>> typeRows = jdbc.queryForList("""
                select code, name, sort from payment_bill_type
                where org_id = ? and code = ? and status = ?
                """, orgId, typeCode, BillTypeService.STATUS_ENABLED);
        if (typeRows.isEmpty()) {
            throw new BizException(BillTypeService.CODE_TYPE_NOT_FOUND, msg("payment.type.notfound"));
        }
        Map<String, Object> type = typeRows.get(0);
        Integer dup = jdbc.queryForObject("""
                select count(*) from payment_bill
                where org_id = ? and biz_addon = ? and biz_type = ? and biz_id = ? and type_code = ?
                  and direction = ?
                """, Integer.class, orgId, bizAddon, bizType, bizId, typeCode, DIRECTION_INCOME);
        if (dup != null && dup > 0) {
            // 修老签约款线「存量已收款 → 硬删重建」:已存在即拒绝,重建是业务插件的事
            throw new BizException(CODE_BILL_DUP, msg("payment.bill.dup"));
        }
        jdbc.update("""
                insert into payment_bill(org_id, biz_addon, biz_type, biz_id, type_code, type_name,
                    direction, amount, sort, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, bizAddon, bizType, bizId, typeCode, (String) type.get("name"),
                DIRECTION_INCOME, amount, ((Number) type.get("sort")).intValue(), blankToNull(remark), createdBy);
        return jdbc.queryForObject("""
                select id from payment_bill where org_id = ? and biz_addon = ? and biz_type = ? and biz_id = ?
                  and type_code = ? and direction = 0
                """, Long.class, orgId, bizAddon, bizType, bizId, typeCode);
    }

    // ---- 收款(守卫三件套 + 顺序门控 + 渠道合计平衡;先验后写) ----

    public record ChannelLine(String channelCode, long amount) {
    }

    @Transactional
    public Map<String, Object> collect(long orgId, long billId, List<ChannelLine> channels, long operatorId) {
        require(channels != null && !channels.isEmpty(), CODE_INVALID_PARAM, "payment.param.invalid");
        long sum = 0;
        for (ChannelLine line : channels) {
            require(line.channelCode() != null && CHANNELS.contains(line.channelCode()),
                    CODE_CHANNEL_INVALID, "payment.bill.channelInvalid");
            require(line.amount() > 0, CODE_CHANNEL_AMOUNT, "payment.bill.channelAmount");
            sum += line.amount();
        }
        BillRow bill = load(orgId, billId);
        require(bill.direction() == DIRECTION_INCOME && bill.status() == STATUS_UNPAID,
                CODE_BILL_BAD_STATE, "payment.bill.badstate");
        if (sum != bill.amount()) {
            // 老守卫三件套第三件(150008 同义):Σ渠道 == 应收
            throw new BizException(CODE_CHANNEL_SUM, msg("payment.bill.channelSum"));
        }
        // 顺序门控:全组前置顺序款必须已收(老只看 sort-1;sort=0 隐蔽线不迁,字典 sort>=1)
        if (bill.sort() > 1) {
            Integer blocked = jdbc.queryForObject("""
                    select count(*) from payment_bill
                    where org_id = ? and biz_addon = ? and biz_type = ? and biz_id = ?
                      and direction = 0 and sort < ? and status not in (1, 2)
                    """, Integer.class, orgId, bill.bizAddon(), bill.bizType(), bill.bizId(), bill.sort());
            if (blocked != null && blocked > 0) {
                throw new BizException(CODE_PREV_NOT_COLLECTED, msg("payment.bill.prevNotCollected"));
            }
        }
        String collectorName = displayName(operatorId);
        // receipt_no NOT NULL 无默认(MySQL 5.6 严格模式):先占位插入,再按 id 回填规范编号(basedata 同款)
        jdbc.update("""
                insert into payment_receipt(org_id, receipt_no, bill_id, direction, amount, collector_id, collector_name, collect_time)
                values (?, concat('R', left(md5(uuid()), 8)), ?,?,?,?,?,now())
                """, orgId, billId, DIRECTION_INCOME, sum, operatorId, collectorName);
        long receiptId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("update payment_receipt set receipt_no = concat('R', lpad(id, 8, '0')) where id = ?", receiptId);
        for (ChannelLine line : channels) {
            jdbc.update("insert into payment_receipt_channel(org_id, receipt_id, channel_code, amount) values (?,?,?,?)",
                    orgId, receiptId, line.channelCode(), line.amount());
        }
        int updated = jdbc.update(
                "update payment_bill set status = ? where id = ? and status = ?",
                STATUS_COLLECTED, billId, STATUS_UNPAID);
        if (updated == 0) {
            // 并发双收款窗口:条件更新只成功一次
            throw new BizException(CODE_BILL_BAD_STATE, msg("payment.bill.badstate"));
        }
        eventBus.publish(new PaymentCollectedEvent(billId, orgId, bill.bizAddon(), bill.bizType(), bill.bizId(),
                bill.typeCode(), bill.amount(), receiptId, operatorId), "bill:" + billId + ":collected");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receiptId", receiptId);
        result.put("receiptNo", "R" + String.format("%08d", receiptId));
        return result;
    }

    // ---- 确认(1→2 终态;collect/confirm 两步保留财务复核,V1 口径) ----

    @Transactional
    public void confirm(long orgId, long billId, long operatorId) {
        BillRow bill = load(orgId, billId);
        require(bill.status() == STATUS_COLLECTED, CODE_BILL_BAD_STATE, "payment.bill.badstate");
        int updated = jdbc.update(
                "update payment_bill set status = ? where id = ? and status = ?",
                STATUS_CONFIRMED, billId, STATUS_COLLECTED);
        if (updated == 0) {
            throw new BizException(CODE_BILL_BAD_STATE, msg("payment.bill.badstate"));
        }
        String confirmerName = displayName(operatorId);
        jdbc.update("""
                update payment_receipt set confirmer_id = ?, confirmer_name = ?, confirm_time = now()
                where bill_id = ? and direction = 0 and confirmer_id is null
                """, operatorId, confirmerName, billId);
        eventBus.publish(new PaymentConfirmedEvent(billId, orgId, bill.bizAddon(), bill.bizType(), bill.bizId(),
                bill.typeCode(), bill.amount(), operatorId), "bill:" + billId + ":confirmed");
    }

    // ---- 退款(统一走内核轻量审批:提交 → 审批办结事件回写,见 RefundApprovalListener) ----

    @Transactional
    public long submitRefund(long orgId, long originBillId, long applicantId, Long assigneeId, String reason) {
        require(assigneeId != null, CODE_INVALID_PARAM, "payment.param.invalid");
        // FOR UPDATE 串行化同一原款项的并发退款提交
        BillRow origin = loadForUpdate(orgId, originBillId);
        require(origin.direction() == DIRECTION_INCOME
                && (origin.status() == STATUS_COLLECTED || origin.status() == STATUS_CONFIRMED),
                CODE_BILL_BAD_STATE, "payment.bill.badstate");
        Integer pending = jdbc.queryForObject("""
                select count(*) from payment_bill where origin_bill_id = ? and direction = ? and status = ?
                """, Integer.class, originBillId, DIRECTION_REFUND, STATUS_UNPAID);
        if (pending != null && pending > 0) {
            throw new BizException(CODE_REFUND_PENDING, msg("payment.bill.refundPending"));
        }
        jdbc.update("""
                insert into payment_bill(org_id, biz_addon, biz_type, biz_id, type_code, type_name,
                    direction, origin_bill_id, origin_status_snapshot, amount, sort, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, origin.bizAddon(), origin.bizType(), origin.bizId(), origin.typeCode(),
                origin.typeName(), DIRECTION_REFUND, originBillId, origin.status(), origin.amount(), 0,
                blankToNull(reason), applicantId);
        long refundBillId = jdbc.queryForObject("select last_insert_id()", Long.class);
        String title = messages.getMessage("payment.refund.approval.title",
                new Object[] { origin.typeName(), yuan(origin.amount()) }, LocaleContextHolder.getLocale());
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(orgId, applicantId, title,
                BIZ_ADDON, BIZ_TYPE, String.valueOf(refundBillId), assigneeId));
        jdbc.update("update payment_bill set refund_approval_id = ? where id = ?", approvalId, refundBillId);
        return refundBillId;
    }

    // ---- 作废(仅未收款;收款后走退款) ----

    @Transactional
    public void cancel(long orgId, long billId) {
        BillRow bill = load(orgId, billId);
        require(bill.direction() == DIRECTION_INCOME && bill.status() == STATUS_UNPAID,
                CODE_BILL_BAD_STATE, "payment.bill.badstate");
        int updated = jdbc.update(
                "update payment_bill set status = ? where id = ? and status = ?",
                STATUS_CANCELED, billId, STATUS_UNPAID);
        if (updated == 0) {
            throw new BizException(CODE_BILL_BAD_STATE, msg("payment.bill.badstate"));
        }
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, String bizAddon, String bizType, String bizId,
            Integer status, Integer direction, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where b.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (bizAddon != null && !bizAddon.isBlank()) {
            where.append(" and b.biz_addon = ?");
            args.add(bizAddon.trim());
        }
        if (bizType != null && !bizType.isBlank()) {
            where.append(" and b.biz_type = ?");
            args.add(bizType.trim());
        }
        if (bizId != null && !bizId.isBlank()) {
            where.append(" and b.biz_id = ?");
            args.add(bizId.trim());
        }
        if (status != null) {
            where.append(" and b.status = ?");
            args.add(status);
        }
        if (direction != null) {
            where.append(" and b.direction = ?");
            args.add(direction);
        }
        Long total = jdbc.queryForObject("select count(*) from payment_bill b" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.id, b.org_id, b.biz_addon, b.biz_type, b.biz_id, b.type_code, b.type_name,
                       b.direction, b.origin_bill_id, b.origin_status_snapshot, b.amount, b.sort, b.status,
                       b.refund_approval_id, b.remark, b.created_by, u.display_name as creator_name,
                       b.created_at, b.updated_at
                from payment_bill b left join yz_user u on u.id = b.created_by
                """ + where + " order by b.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long billId) {
        BillRow bill = load(orgId, billId);
        Map<String, Object> result = jdbc.queryForMap("""
                select b.*, u.display_name as creator_name from payment_bill b
                left join yz_user u on u.id = b.created_by where b.id = ?
                """, billId);
        List<Map<String, Object>> receipts = jdbc.queryForList("""
                select id, receipt_no, direction, amount, collector_id, collector_name, collect_time,
                       confirmer_id, confirmer_name, confirm_time, created_at
                from payment_receipt where bill_id = ? and org_id = ? order by id
                """, billId, orgId);
        for (Map<String, Object> receipt : receipts) {
            receipt.put("channels", jdbc.queryForList("""
                    select channel_code, amount from payment_receipt_channel
                    where receipt_id = ? order by id
                    """, ((Number) receipt.get("id")).longValue()));
        }
        List<Map<String, Object>> refunds = jdbc.queryForList("""
                select id, status, amount, origin_status_snapshot, refund_approval_id, remark, created_at
                from payment_bill where origin_bill_id = ? and direction = 1 order by id
                """, billId);
        result.put("receipts", receipts);
        result.put("refunds", refunds);
        return result;
    }

    /** 退款单回读(审批中心/冒烟用):按 id,任意 direction。 */
    public Map<String, Object> get(long orgId, long billId) {
        load(orgId, billId);
        return jdbc.queryForMap("select * from payment_bill where id = ?", billId);
    }

    // ---- 内部 ----

    private BillRow load(long orgId, long billId) {
        List<BillRow> rows = jdbc.query("""
                select id, org_id, biz_addon, biz_type, biz_id, type_code, type_name, direction,
                       origin_bill_id, origin_status_snapshot, amount, sort, status, refund_approval_id
                from payment_bill where id = ? and org_id = ?
                """, (rs, rowNum) -> new BillRow(rs.getLong("id"), rs.getLong("org_id"),
                rs.getString("biz_addon"), rs.getString("biz_type"), rs.getString("biz_id"),
                rs.getString("type_code"), rs.getString("type_name"), rs.getInt("direction"),
                rs.getObject("origin_bill_id") == null ? null : rs.getLong("origin_bill_id"),
                rs.getObject("origin_status_snapshot") == null ? null : rs.getInt("origin_status_snapshot"),
                rs.getLong("amount"), rs.getInt("sort"), rs.getInt("status"),
                rs.getObject("refund_approval_id") == null ? null : rs.getLong("refund_approval_id")),
                billId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_BILL_NOT_FOUND, msg("payment.bill.notfound"));
        }
        return rows.get(0);
    }

    private BillRow loadForUpdate(long orgId, long billId) {
        jdbc.queryForList("select id from payment_bill where id = ? and org_id = ? for update", billId, orgId);
        return load(orgId, billId);
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    /** 厘 → 元展示(审批标题用);三位小数。 */
    private String yuan(long amount) {
        return java.math.BigDecimal.valueOf(amount, 3).toPlainString();
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
