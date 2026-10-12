package com.yingzao.addon.payment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.PaymentRefundedEvent;
import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 退款审批办结回写消费器(内核事件总线 SPI):只处理 biz 三元组 = (payment, bill, 退款款项单id)。
 * 通过 → 原款项置 3 已退款(条件 UPDATE 守卫)+ 退款单置 1 已退款 + 落退款收据(执行人快照)+ 发 PaymentRefundedEvent;
 * 驳回/撤回 → 退款单置 4 已作废,原款项零改动(原状态快照在退款单上——修老「驳回顶态」缺陷)。
 * 全部回写带 refund_approval_id 守卫(只认当前挂着的审批单),陈旧事件天然被拒;
 * 守卫失配一律 log.error 可见(禁静默跳过红线),不抛——重放不会变好。
 * 取代原 RefundApprovalListener 的 Spring AFTER_COMMIT 监听(第 39 次运行迁总线):
 * 回写逻辑在本消费器内直接操作 JDBC,故 consume 带 @Transactional(当前无事务上下文,进方法即新建,
 * 语义等价旧 REQUIRES_NEW)保「原款项+退款单+收据+退款事件出站箱」同事务原子;
 * 退款事件经总线发布(outbox 与回写同事务,较旧姿势 AFTER_COMMIT 事件更原子),失败由总线重试。
 */
@Component
public class RefundApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundApprovalConsumer.class);

    private final JdbcTemplate jdbc;
    private final YzEventBus eventBus;

    public RefundApprovalConsumer(JdbcTemplate jdbc, @Lazy YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.eventBus = eventBus;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    @Transactional
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!BillService.BIZ_ADDON.equals(payload.bizAddon()) || !BillService.BIZ_TYPE.equals(payload.bizType())) {
            return;
        }
        long refundBillId;
        try {
            refundBillId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.error("refund approval finished with unparsable bizId={}, approvalId={}",
                    payload.bizId(), payload.approvalId());
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, biz_addon, biz_type, biz_id, type_code, type_name, amount,
                       status, refund_approval_id
                from payment_bill where id = ? and direction = 1
                """, refundBillId);
        if (rows.isEmpty()) {
            log.error("refund approval finished but refund bill {} missing, approvalId={}",
                    refundBillId, payload.approvalId());
            return;
        }
        Map<String, Object> refund = rows.get(0);
        long approvalId = ((Number) refund.get("refund_approval_id")).longValue();
        if (approvalId != payload.approvalId()) {
            log.error("refund bill {} guarded by approval {} but finished event came from {}, ignored",
                    refundBillId, approvalId, payload.approvalId());
            return;
        }
        int status = ((Number) refund.get("status")).intValue();
        if (status != BillService.STATUS_UNPAID) {
            log.error("refund bill {} already terminal ({}), approval {} event ignored",
                    refundBillId, status, payload.approvalId());
            return;
        }
        long orgId = ((Number) refund.get("org_id")).longValue();
        if (payload.result() == ApprovalFinishedEvent.RESULT_APPROVED) {
            execute(orgId, refund, approvalId, payload.operatorId());
        } else if (payload.result() == ApprovalFinishedEvent.RESULT_REJECTED
                || payload.result() == ApprovalFinishedEvent.RESULT_CANCELED) {
            dismiss(refundBillId);
        }
    }

    /** 审批通过:原款项 → 3 已退款(终态),退款单 → 1 已退款,落退款收据,发退款事件。 */
    private void execute(long orgId, Map<String, Object> refund, long approvalId, long operatorId) {
        long refundBillId = ((Number) refund.get("id")).longValue();
        Long originBillId = jdbc.queryForObject(
                "select origin_bill_id from payment_bill where id = ?", Long.class, refundBillId);
        int originUpdated = jdbc.update("""
                update payment_bill set status = ? where id = ? and status in (?, ?)
                """, BillService.STATUS_REFUNDED, originBillId,
                BillService.STATUS_COLLECTED, BillService.STATUS_CONFIRMED);
        if (originUpdated == 0) {
            log.error("refund bill {} approved but origin bill {} not in collected/confirmed, kept untouched",
                    refundBillId, originBillId);
            return;
        }
        int refundUpdated = jdbc.update("""
                update payment_bill set status = ? where id = ? and status = ? and refund_approval_id = ?
                """, BillService.STATUS_REFUNDED, refundBillId, BillService.STATUS_UNPAID, approvalId);
        if (refundUpdated == 0) {
            log.error("refund bill {} failed to mark executed (race on terminal), origin {} already refunded",
                    refundBillId, originBillId);
            return;
        }
        String operatorName = jdbc.queryForObject(
                "select display_name from yz_user where id = ?", String.class, operatorId);
        // receipt_no NOT NULL 无默认:占位插入后按 id 回填规范编号(与收款同款)
        jdbc.update("""
                insert into payment_receipt(org_id, receipt_no, bill_id, direction, amount, collector_id, collector_name, collect_time)
                values (?, concat('R', left(md5(uuid()), 8)), ?,?,?,?,?,now())
                """, orgId, refundBillId, BillService.DIRECTION_REFUND,
                ((Number) refund.get("amount")).longValue(), operatorId, operatorName);
        long receiptId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("update payment_receipt set receipt_no = concat('R', lpad(id, 8, '0')) where id = ?", receiptId);
        eventBus.publish(new PaymentRefundedEvent(refundBillId, originBillId, orgId,
                (String) refund.get("biz_addon"), (String) refund.get("biz_type"), (String) refund.get("biz_id"),
                (String) refund.get("type_code"), ((Number) refund.get("amount")).longValue(),
                approvalId, operatorId), "refund:" + refundBillId + ":refunded");
    }

    /** 驳回/撤回:退款单置 4 已作废;原款项零改动(修老「驳回顶态」)。 */
    private void dismiss(long refundBillId) {
        int updated = jdbc.update(
                "update payment_bill set status = ? where id = ? and status = ?",
                BillService.STATUS_CANCELED, refundBillId, BillService.STATUS_UNPAID);
        if (updated == 0) {
            log.error("refund bill {} dismiss lost race (already terminal)", refundBillId);
        }
    }
}
