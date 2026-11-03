package com.yingzao.addon.payment;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yingzao.addons.api.PaymentApi;
import com.yingzao.core.KernelTestBase;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventBus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4b 单测批(第 125 次):payment 款项终态守卫回归(docs/15 R28)。
 * 关键守卫锚定历史事故:
 * <ul>
 *   <li>createBill 活单查重语义 ← 第 67 次「查重不排终态:退定金后重开同类型款线被拒死信」;</li>
 *   <li>last_insert_id 连接级回取 ← 第 67 次「按业务键回取命中终态历史单多行炸」;</li>
 *   <li>collect 顺序门控 270010/渠道守卫三件套/条件更新 ← 老先写后验与读-判-写家族;</li>
 *   <li>submitRefundPartial 金额闸/幂等闸/部分退原单不置终态 ← 第 81 次定案;</li>
 *   <li>collectBatch 先验后写 ← 第 83 次批收定案(任一笔非法整批拒零写入)。</li>
 * </ul>
 */
class PaymentBillLifecycleTest extends KernelTestBase {

    private static final long ORG = 1L;
    private static final String ADDON = "sales";
    private static final String BIZ_TYPE = "order";
    private static final String BIZ_ID = "501";

    private BillService bills;

    @BeforeEach
    void setUp() {
        // 显式 id=1:类内多测试共享同库,delete 后 H2 自增不归零,服务侧按 id=1 取人(displayName/requireOrgUser)
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) values (1,?,?,?,?)",
                ORG, "op", "p", "运营");
        YzEventBus bus = new YzEventBus(jdbc, List.of(), 60, 3600, 5);
        ApprovalService approvals = new ApprovalService(jdbc, STUB_MESSAGES, bus);
        bills = new BillService(jdbc, STUB_MESSAGES, bus, approvals);
        type("contract", "合同款", 1);
        type("sales_deposit", "定金", 2);
    }

    private void type(String code, String name, int sort) {
        jdbc.update("insert into payment_bill_type(org_id, code, name, sort) values (?,?,?,?)",
                ORG, code, name, sort);
    }

    private long bill(String typeCode, int status, int sort, long amount) {
        return billIn(BIZ_ID, typeCode, status, sort, amount);
    }

    private long billIn(String bizId, String typeCode, int status, int sort, long amount) {
        jdbc.update("""
                insert into payment_bill(org_id, biz_addon, biz_type, biz_id, type_code, type_name,
                    direction, amount, sort, status)
                values (?,?,?,?,?,?,0,?,?,?)
                """, ORG, ADDON, BIZ_TYPE, bizId, typeCode, typeCode, amount, sort, status);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private long newBill(String typeCode, long amount) {
        return bills.createBill(ORG, ADDON, BIZ_TYPE, BIZ_ID, typeCode, amount, null, 1L);
    }

    // ---- createBill:活单语义(第 67 次锚) ----

    @Test
    void createBillRequiresEnabledType() {
        expectBiz(BillTypeService.CODE_TYPE_NOT_FOUND,
                () -> bills.createBill(ORG, ADDON, BIZ_TYPE, BIZ_ID, "nope", 100000, null, 1L));
    }

    @Test
    void createBillRejectsActiveDupForAllThreeLiveStates() {
        // 活单(未收/已收/已确认)存在即拒——老「存量已收款硬删重建」的正面闸
        for (int liveStatus = 0; liveStatus <= 2; liveStatus++) {
            bill("contract", liveStatus, 1, 100000);
            expectBiz(BillService.CODE_BILL_DUP,
                    () -> bills.createBill(ORG, ADDON, BIZ_TYPE, BIZ_ID, "contract", 100000, null, 1L));
            jdbc.update("delete from payment_bill");
        }
    }

    @Test
    void createBillAllowsRecreateAfterTerminalAndReturnsNewId() {
        // 第 67 次回归锚:终态(已退款/已作废)不占同类型名额;回取 last_insert_id 不按业务键(终态历史单多行炸)
        long refunded = bill("contract", BillService.STATUS_REFUNDED, 1, 100000);
        long canceled = bill("contract", BillService.STATUS_CANCELED, 1, 80000);
        long fresh = bills.createBill(ORG, ADDON, BIZ_TYPE, BIZ_ID, "contract", 100000, null, 1L);
        assertNotEquals(refunded, fresh);
        assertNotEquals(canceled, fresh);
        Integer rows = jdbc.queryForObject(
                "select count(*) from payment_bill where org_id = ? and type_code = 'contract'", Integer.class, ORG);
        assertEquals(3, rows);
    }

    // ---- collect:守卫三件套 + 顺序门控 + 条件更新 ----

    @Test
    void collectGuardsChannelTriplet() {
        long id = newBill("contract", 100000);
        expectBiz(BillService.CODE_CHANNEL_INVALID,
                () -> bills.collect(ORG, id, List.of(new BillService.ChannelLine("wechat", 100000)), 1L));
        expectBiz(BillService.CODE_CHANNEL_AMOUNT,
                () -> bills.collect(ORG, id, List.of(new BillService.ChannelLine("cash", 0)), 1L));
        expectBiz(BillService.CODE_CHANNEL_SUM,
                () -> bills.collect(ORG, id, List.of(new BillService.ChannelLine("cash", 99999)), 1L));
    }

    @Test
    void collectRejectsNonUnpaidBill() {
        long collected = bill("contract", BillService.STATUS_COLLECTED, 1, 100000);
        expectBiz(BillService.CODE_BILL_BAD_STATE,
                () -> bills.collect(ORG, collected, List.of(new BillService.ChannelLine("cash", 100000)), 1L));
    }

    @Test
    void collectEnforcesSequentialGating() {
        // 定金线 sort=2:前置合同款(sort=1)未收即 270010——第 65 次定金拆线的顺序门控
        long contract = bill("contract", BillService.STATUS_UNPAID, 1, 100000);
        long deposit = bill("sales_deposit", BillService.STATUS_UNPAID, 2, 50000);
        expectBiz(BillService.CODE_PREV_NOT_COLLECTED,
                () -> bills.collect(ORG, deposit, List.of(new BillService.ChannelLine("cash", 50000)), 1L));
        bills.collect(ORG, contract, List.of(new BillService.ChannelLine("cash", 100000)), 1L);
        bills.collect(ORG, deposit, List.of(new BillService.ChannelLine("cash", 50000)), 1L);
        assertEquals(BillService.STATUS_COLLECTED, statusOf(contract));
        assertEquals(BillService.STATUS_COLLECTED, statusOf(deposit));
    }

    @Test
    void collectGatingSatisfiedByCollectedOrConfirmed() {
        // 门控谓词 = 前置款 status not in (1,2):已收与已确认都算已满足
        bill("contract", BillService.STATUS_CONFIRMED, 1, 100000);
        long deposit = bill("sales_deposit", BillService.STATUS_UNPAID, 2, 50000);
        bills.collect(ORG, deposit, List.of(new BillService.ChannelLine("cash", 50000)), 1L);
        assertEquals(BillService.STATUS_COLLECTED, statusOf(deposit));
    }

    @Test
    void collectThenCollectAgainHitsConditionalUpdateGuard() {
        long id = newBill("contract", 100000);
        bills.collect(ORG, id, List.of(new BillService.ChannelLine("cash", 100000)), 1L);
        // 二次收款:load 即见 status=1,先验 270003(条件更新双保险在并发窗口才触发)
        expectBiz(BillService.CODE_BILL_BAD_STATE,
                () -> bills.collect(ORG, id, List.of(new BillService.ChannelLine("cash", 100000)), 1L));
    }

    @Test
    void collectBatchRejectsWholeBatchWhenAnyInvalid() {
        // 第 83 次批收定案:先验后写,任一笔非法整批拒零写入
        long ok = bill("contract", BillService.STATUS_UNPAID, 1, 100000);
        long collected = bill("contract", BillService.STATUS_COLLECTED, 1, 50000);
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.collectBatch(ORG, List.of(ok, collected),
                List.of(new BillService.ChannelLine("cash", 150000)), 1L));
        Integer receipts = jdbc.queryForObject("select count(*) from payment_receipt", Integer.class);
        assertEquals(0, receipts);
        assertEquals(BillService.STATUS_UNPAID, statusOf(ok));
    }

    // ---- confirm/cancel:终态守卫 ----

    @Test
    void confirmLifecycleTerminalGuards() {
        long id = newBill("contract", 100000);
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.confirm(ORG, id, 1L));
        bills.collect(ORG, id, List.of(new BillService.ChannelLine("cash", 100000)), 1L);
        bills.confirm(ORG, id, 1L);
        assertEquals(BillService.STATUS_CONFIRMED, statusOf(id));
        Integer confirmer = jdbc.queryForObject(
                "select count(*) from payment_receipt where bill_id = ? and confirmer_id = 1", Integer.class, id);
        assertEquals(1, confirmer);
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.confirm(ORG, id, 1L));
    }

    @Test
    void cancelOnlyUnpaidAndTerminalReopensTypeSlot() {
        long unpaid = billIn("601", "contract", BillService.STATUS_UNPAID, 1, 100000);
        long collected = billIn("602", "contract", BillService.STATUS_COLLECTED, 1, 100000);
        bills.cancel(ORG, unpaid);
        assertEquals(BillService.STATUS_CANCELED, statusOf(unpaid));
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.cancel(ORG, collected));
        // 作废=终态:同业务键同类型可重开(退定金重收同语义)
        long reopened = bills.createBill(ORG, ADDON, BIZ_TYPE, "601", "contract", 100000, null, 1L);
        assertNotEquals(unpaid, reopened);
        // 已收(活单)仍占名额:重开正确被拒
        expectBiz(BillService.CODE_BILL_DUP,
                () -> bills.createBill(ORG, ADDON, BIZ_TYPE, "602", "contract", 100000, null, 1L));
    }

    // ---- 退款:金额闸/幂等闸/原单不置终态(第 81 次定案) ----

    @Test
    void refundRequiresCollectedOrConfirmedOrigin() {
        long unpaid = bill("contract", BillService.STATUS_UNPAID, 1, 100000);
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.submitRefundPartial(
                new PaymentApi.PartialRefundCmd(ORG, unpaid, null, 1L, 1L, "退")));
        long canceled = bill("contract", BillService.STATUS_CANCELED, 1, 100000);
        expectBiz(BillService.CODE_BILL_BAD_STATE, () -> bills.submitRefundPartial(
                new PaymentApi.PartialRefundCmd(ORG, canceled, null, 1L, 1L, "退")));
    }

    @Test
    void partialRefundAmountGuard() {
        long origin = bill("contract", BillService.STATUS_COLLECTED, 1, 100000);
        expectBiz(BillService.CODE_INVALID_PARAM, () -> bills.submitRefundPartial(
                new PaymentApi.PartialRefundCmd(ORG, origin, 0L, 1L, 1L, "退")));
        expectBiz(BillService.CODE_INVALID_PARAM, () -> bills.submitRefundPartial(
                new PaymentApi.PartialRefundCmd(ORG, origin, 100001L, 1L, 1L, "退")));
        bills.submitRefundPartial(new PaymentApi.PartialRefundCmd(ORG, origin, 100000L, 1L, 1L, "足额"));
        assertEquals(Long.valueOf(100000L), refundAmountOf(origin));
    }

    @Test
    void partialRefundKeepsOriginStatusAndRecordsSnapshot() {
        long origin = bill("contract", BillService.STATUS_COLLECTED, 1, 100000);
        bills.submitRefundPartial(new PaymentApi.PartialRefundCmd(ORG, origin, 30000L, 1L, 1L, "部分退"));
        // 部分退原单不动,净额由聚合体现(第 81 次定案)
        assertEquals(BillService.STATUS_COLLECTED, statusOf(origin));
        assertEquals(Long.valueOf(30000L), refundAmountOf(origin));
        Integer snapshot = jdbc.queryForObject(
                "select origin_status_snapshot from payment_bill where origin_bill_id = ? and direction = 1",
                Integer.class, origin);
        assertEquals(BillService.STATUS_COLLECTED, snapshot);
    }

    @Test
    void refundPendingGateBlocksConcurrentSecondSubmit() {
        long origin = bill("contract", BillService.STATUS_COLLECTED, 1, 100000);
        bills.submitRefundPartial(new PaymentApi.PartialRefundCmd(ORG, origin, 30000L, 1L, 1L, "第一笔"));
        expectBiz(BillService.CODE_REFUND_PENDING, () -> bills.submitRefundPartial(
                new PaymentApi.PartialRefundCmd(ORG, origin, 20000L, 1L, 1L, "第二笔")));
    }

    @Test
    void fullRefundRecordsSnapshotAndKernelApproval() {
        long origin = bill("contract", BillService.STATUS_CONFIRMED, 1, 100000);
        long refundId = bills.submitRefund(ORG, origin, 1L, 1L, "整单退");
        assertEquals(Long.valueOf(100000L), refundAmountOf(origin));
        Integer approvalCount = jdbc.queryForObject("""
                select count(*) from yz_approval
                where biz_addon = 'payment' and biz_type = 'bill' and biz_id = ?
                """, Integer.class, String.valueOf(refundId));
        assertEquals(1, approvalCount);
        Long linked = jdbc.queryForObject(
                "select refund_approval_id from payment_bill where id = ?", Long.class, refundId);
        assertTrue(linked != null && linked > 0);
    }

    // ---- 工具 ----

    private int statusOf(long billId) {
        return jdbc.queryForObject("select status from payment_bill where id = ?", Integer.class, billId);
    }

    private Long refundAmountOf(long originBillId) {
        return jdbc.queryForObject(
                "select amount from payment_bill where origin_bill_id = ? and direction = 1",
                Long.class, originBillId);
    }
}
