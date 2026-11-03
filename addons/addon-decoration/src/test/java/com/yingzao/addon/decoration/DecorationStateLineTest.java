package com.yingzao.addon.decoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yingzao.addons.api.CustomerProfileApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.QuotationApi;
import com.yingzao.core.KernelTestBase;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventBus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F4b 单测批(第 125 次):decoration 四条状态线守卫回归(docs/15 R28)。
 * 四线 = 合同(status)/工程(project_status)/设计(design_status)/确认(source_status),
 * 显式迁移表白名单(TRANSITIONS)统一守卫 ← 修老「裸写状态机/跳号过渡态」家族:
 * <ul>
 *   <li>合同线:白名单外迁移 250002/签约三闸(250010/250012/250009)/重签前置 250024/
 *       废单联动作废未收款/终止审批流(250013+陈旧事件守卫)/决算硬闸 250016;</li>
 *   <li>确认线:250015 未签约不可确认 + %10==1 谓词(0 不可跳步/2 不可倒退);</li>
 *   <li>工程线:开工/验收条件 UPDATE 幂等,重放零重复轨迹,验收不越级;</li>
 *   <li>首收升定金:status==0 才走(事件重放幂等)。</li>
 * </ul>
 */
class DecorationStateLineTest extends KernelTestBase {

    private static final long ORG = 1L;
    private static final long OP = 1L;

    private StubPayment payments;
    private StubQuotation quotations;
    private DecorationService decorations;

    @BeforeEach
    void setUp() {
        // 显式 id=1:类内多测试共享同库,delete 后 H2 自增不归零,服务侧按 id=1 取人(displayName/requireOrgUser)
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) values (1,?,?,?,?)",
                ORG, "op", "p", "运营");
        YzEventBus bus = new YzEventBus(jdbc, List.of(), 60, 3600, 5);
        ApprovalService approvals = new ApprovalService(jdbc, STUB_MESSAGES, bus);
        payments = new StubPayment();
        quotations = new StubQuotation();
        StubProfiles profiles = new StubProfiles();
        // 总闸关(默认 check_enable=0,ensureConfig 懒建)= 款项全收齐硬闸;六项检查面不在本批
        DecorationAccountCheckService accountChecks =
                new DecorationAccountCheckService(jdbc, STUB_MESSAGES, null, null, null);
        decorations = new DecorationService(jdbc, STUB_MESSAGES, bus, payments, profiles,
                quotations, approvals, accountChecks);
    }

    /** 合同线默认 0 登记;可指定各线初值。 */
    private long order(int status, int sourceStatus) {
        jdbc.update("""
                insert into decoration_order(org_id, order_no, customer_id, customer_name, house_id,
                    house_label, type, status, source_status, designer_id, salesman_id, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, ORG, "YZT" + System.nanoTime(), 11L, "张三", 22L, "恒大绿洲3-1-101",
                0, status, sourceStatus, 8L, 9L, OP);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    // ---- 合同线:显式迁移白名单 ----

    @Test
    void transitionWhitelistRejectsIllegalJumps() {
        long signing = order(3, 0);
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.finish(ORG, signing, OP));
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.obsolete(ORG, signing, OP));
        long resigned = order(4, 0);
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.account(ORG, resigned, OP));
        long presigned = order(2, 0);
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.resign(ORG, presigned, OP));
        long finished = order(8, 0);
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.finish(ORG, finished, OP));
    }

    @Test
    void signGuardsAmountApprovalAndPayment() {
        long id = order(2, 0);
        expectBiz(DecorationService.CODE_SIGN_AMOUNT, () -> decorations.sign(ORG, id, 0L, OP));
        expectBiz(DecorationService.CODE_SIGN_AMOUNT, () -> decorations.sign(ORG, id, null, OP));
        jdbc.update("""
                insert into yz_approval(org_id, title, biz_addon, biz_type, biz_id, applicant_id, assignee_id)
                values (?,?,?,?,?,?,?)
                """, ORG, "t", "decoration", "order", String.valueOf(id), 1L, 1L);
        expectBiz(DecorationService.CODE_APPROVAL_PENDING, () -> decorations.sign(ORG, id, 2000000L, OP));
        jdbc.update("delete from yz_approval");
        payments.billList = List.of(new PaymentApi.BillSummary(11L, "deposit", "定金", 0, 50000, 1, 0));
        expectBiz(DecorationService.CODE_SIGN_NO_PAYMENT, () -> decorations.sign(ORG, id, 2000000L, OP));
        payments.billList = List.of(new PaymentApi.BillSummary(11L, "deposit", "定金", 0, 50000, 1, 1));
        decorations.sign(ORG, id, 2000000L, OP);
        assertEquals(3, statusOf(id));
        assertNotNull(timeOf(id, "contract_time"));
    }

    @Test
    void presignRequiresProfileAndLockedQuotation() {
        long id = order(0, 0);
        expectBiz(DecorationService.CODE_PRESIGN_NO_PROFILE, () -> decorations.presign(ORG, id, OP));
        jdbc.update("insert into decoration_order_profile(order_id, org_id) values (?,?)", id, ORG);
        quotations.locked = false;
        expectBiz(DecorationService.CODE_PRESIGN_NO_QUOTATION, () -> decorations.presign(ORG, id, OP));
        quotations.locked = true;
        decorations.presign(ORG, id, OP);
        assertEquals(2, statusOf(id));
    }

    @Test
    void resignRequiresNoUnconfirmedAddReduceAndLoopsBackViaSign() {
        long id = order(3, 0);
        quotations.unconfirmedAddReduce = true;
        expectBiz(DecorationService.CODE_AR_PENDING, () -> decorations.resign(ORG, id, OP));
        quotations.unconfirmedAddReduce = false;
        decorations.resign(ORG, id, OP);
        assertEquals(4, statusOf(id));
        assertNotNull(timeOf(id, "recontract_time"));
        // 重签回签循环:4 → 3 经 sign(TRANSITIONS 4→{3})
        payments.billList = List.of(new PaymentApi.BillSummary(11L, "deposit", "定金", 0, 50000, 1, 1));
        decorations.sign(ORG, id, 2000000L, OP);
        assertEquals(3, statusOf(id));
    }

    @Test
    void obsoleteCancelsUnpaidBillsOnly() {
        long id = order(0, 0);
        payments.billList = List.of(
                new PaymentApi.BillSummary(21L, "deposit", "定金", 0, 50000, 1, 0),
                new PaymentApi.BillSummary(22L, "deposit", "定金", 0, 50000, 1, 1));
        decorations.obsolete(ORG, id, OP);
        assertEquals(5, statusOf(id));
        assertEquals(List.of(21L), payments.canceled);
        // 终态 5:任何再迁移 250002
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.obsolete(ORG, id, OP));
    }

    @Test
    void terminationFlowWithStaleApprovalGuard() {
        long id = order(2, 0);
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION,
                () -> decorations.submitTermination(ORG, id, 1L, "原因", OP));
        long signed = order(3, 0);
        long approvalId = decorations.submitTermination(ORG, signed, 1L, "业主原因", OP);
        assertEquals(ApprovalService.STATUS_RUNNING, jdbc.queryForObject(
                "select status from yz_approval where id = ?", Integer.class, approvalId));
        assertEquals(Long.valueOf(approvalId), jdbc.queryForObject(
                "select termination_approval_id from decoration_order where id = ?", Long.class, signed));
        // 同单进行中终止审批:250013(显式审批单代替老跳号过渡态 32/33)
        expectBiz(DecorationService.CODE_TERMINATION_PENDING,
                () -> decorations.submitTermination(ORG, signed, 1L, "again", OP));
        // 陈旧事件守卫:approval_id 与单上挂载不匹配即拒
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION,
                () -> decorations.executeTermination(ORG, signed, approvalId + 999, OP));
        payments.billList = List.of(new PaymentApi.BillSummary(31L, "contract", "合同款", 0, 100000, 1, 0));
        jdbc.update("insert into decoration_order_belong(org_id, order_id, status) values (?,?,1)", ORG, signed);
        decorations.executeTermination(ORG, signed, approvalId, OP);
        assertEquals(6, statusOf(signed));
        assertEquals(List.of(31L), payments.canceled);
        assertEquals(2, jdbc.queryForObject(
                "select status from decoration_order_belong where order_id = ?", Integer.class, signed));
    }

    @Test
    void accountRequiresAllBillsCollectedThenFinishTerminal() {
        long id = order(3, 0);
        payments.billList = List.of(
                new PaymentApi.BillSummary(41L, "contract", "合同款", 0, 100000, 1, 1),
                new PaymentApi.BillSummary(42L, "addreduce", "增减款", 0, 50000, 1, 0));
        expectBiz(DecorationService.CODE_ACCOUNT_NOT_COLLECTED, () -> decorations.account(ORG, id, OP));
        payments.billList = List.of(
                new PaymentApi.BillSummary(41L, "contract", "合同款", 0, 100000, 1, 2),
                new PaymentApi.BillSummary(42L, "addreduce", "增减款", 0, 50000, 1, 1));
        decorations.account(ORG, id, OP);
        assertEquals(7, statusOf(id));
        assertNotNull(timeOf(id, "account_time"));
        decorations.finish(ORG, id, OP);
        assertEquals(8, statusOf(id));
        expectBiz(DecorationService.CODE_ILLEGAL_TRANSITION, () -> decorations.finish(ORG, id, OP));
    }

    // ---- 确认线(source_status):%10==1 谓词 ----

    @Test
    void sourceConfirmRequiresSignedFamilyFirst() {
        long id = order(0, 0);
        expectBiz(DecorationService.CODE_SOURCE_NOT_SIGNED,
                () -> decorations.designConfirm(ORG, id, 0, OP));
        expectBiz(DecorationService.CODE_SOURCE_NOT_SIGNED,
                () -> decorations.drawlineConfirm(ORG, id, OP));
    }

    @Test
    void sourceConfirmStepSequenceAndVariants() {
        long id = order(2, 0);
        // 0 待确认不可跳步
        expectBiz(DecorationService.CODE_SOURCE_BAD_STEP, () -> decorations.drawlineConfirm(ORG, id, OP));
        decorations.designConfirm(ORG, id, 0, OP);
        assertEquals(1, sourceOf(id));
        assertEquals(1, designOf(id));
        // 2 已放线确认不可倒退
        decorations.drawlineConfirm(ORG, id, OP);
        assertEquals(2, sourceOf(id));
        assertEquals(2, designOf(id));
        expectBiz(DecorationService.CODE_SOURCE_BAD_STEP, () -> decorations.designConfirm(ORG, id, 0, OP));
        // 变体:微改 11 / 大改 21(%10==1 仍为合同确认位)
        long minor = order(3, 0);
        decorations.designConfirm(ORG, minor, 1, OP);
        assertEquals(11, sourceOf(minor));
        decorations.drawlineConfirm(ORG, minor, OP);
        assertEquals(2, sourceOf(minor));
        long major = order(3, 0);
        decorations.designConfirm(ORG, major, 2, OP);
        assertEquals(21, sourceOf(major));
        expectBiz(DecorationService.CODE_INVALID_PARAM, () -> decorations.designConfirm(ORG, major, 3, OP));
    }

    // ---- 工程线:条件 UPDATE 幂等,重放零重复 ----

    @Test
    void projectLineEventsIdempotent() {
        long id = order(3, 0);
        decorations.onConstructionStarted(ORG, id, OP);
        assertEquals(1, projectOf(id));
        decorations.onConstructionStarted(ORG, id, OP);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from decoration_order_track where order_id = ? and action = 'constructionStart'",
                Integer.class, id));
        decorations.onConstructionAccepted(ORG, id, OP);
        assertEquals(2, projectOf(id));
        // 未开工(0)不越级:验收事件静默放行
        long fresh = order(3, 0);
        decorations.onConstructionAccepted(ORG, fresh, OP);
        assertEquals(0, projectOf(fresh));
    }

    // ---- 首收升定金:status==0 才走 ----

    @Test
    void firstCollectedPromotesDepositOnlyFromInit() {
        long id = order(0, 0);
        decorations.onPaymentCollected(ORG, id, DecorationService.TYPE_CODE_DEPOSIT, OP);
        assertEquals(1, statusOf(id));
        assertNotNull(timeOf(id, "deposit_time"));
        // 事件重放/已推进:幂等返回
        decorations.onPaymentCollected(ORG, id, DecorationService.TYPE_CODE_DEPOSIT, OP);
        assertEquals(1, statusOf(id));
        long presigned = order(2, 0);
        decorations.onPaymentCollected(ORG, presigned, DecorationService.TYPE_CODE_DEPOSIT, OP);
        assertEquals(2, statusOf(presigned));
        // 非定金类型:零动作
        long other = order(0, 0);
        decorations.onPaymentCollected(ORG, other, "contract", OP);
        assertEquals(0, statusOf(other));
    }

    private int statusOf(long orderId) {
        return jdbc.queryForObject("select status from decoration_order where id = ?", Integer.class, orderId);
    }

    private int sourceOf(long orderId) {
        return jdbc.queryForObject("select source_status from decoration_order where id = ?", Integer.class, orderId);
    }

    private int designOf(long orderId) {
        return jdbc.queryForObject("select design_status from decoration_order where id = ?", Integer.class, orderId);
    }

    private int projectOf(long orderId) {
        return jdbc.queryForObject("select project_status from decoration_order where id = ?", Integer.class, orderId);
    }

    private java.sql.Timestamp timeOf(long orderId, String column) {
        return jdbc.queryForObject("select " + column + " from decoration_order where id = ?",
                java.sql.Timestamp.class, orderId);
    }

    // ---- 契约 stub ----

    static class StubPayment implements PaymentApi {
        final List<BillSummary> billListState = new ArrayList<>();
        List<BillSummary> billList = List.of();
        final List<Long> canceled = new ArrayList<>();

        @Override
        public long createBill(CreateBillCmd cmd) {
            return 0;
        }

        @Override
        public long advanceCharge(AdvanceCmd cmd) {
            return 0;
        }

        @Override
        public long advanceDeduct(AdvanceCmd cmd) {
            return 0;
        }

        @Override
        public long advanceBalance(long orgId, String subjectCode, String refType, String refId) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> listBillTypes(long orgId) {
            return List.of();
        }

        @Override
        public List<BillSummary> listBills(long orgId, String bizAddon, String bizType, String bizId) {
            return billList;
        }

        @Override
        public void cancelBill(long orgId, long billId) {
            canceled.add(billId);
        }

        @Override
        public long submitRefund(RefundCmd cmd) {
            return 0;
        }

        @Override
        public long submitRefundPartial(PartialRefundCmd cmd) {
            return 0;
        }
    }

    static class StubQuotation implements QuotationApi {
        boolean locked = false;
        boolean unconfirmedAddReduce = false;

        @Override
        public boolean hasLockedQuotation(long orgId, long orderId) {
            return locked;
        }

        @Override
        public Optional<QuotationSummary> queryDefaultQuotation(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public java.util.Optional<Long> designatedMaterialPackageId(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public boolean hasUnconfirmedAddReduce(long orgId, long orderId) {
            return unconfirmedAddReduce;
        }

        @Override
        public Optional<QuotationBreakdown> defaultQuotationBreakdown(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public java.util.Set<Long> activeQuoteOrderIds(long orgId) {
            return java.util.Set.of();
        }
    }

    static class StubProfiles implements CustomerProfileApi {
        @Override
        public Optional<CustomerSummary> queryCustomer(long orgId, long customerId) {
            return Optional.empty();
        }

        @Override
        public long ensureCustomerByPhone(long orgId, String mobile) {
            return 0;
        }

        @Override
        public Optional<HouseSummary> queryHouse(long orgId, long houseId) {
            return Optional.empty();
        }
    }
}
