package com.yingzao.addon.quotation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.MaterialQuotaApi;
import com.yingzao.core.KernelTestBase;
import com.yingzao.core.eventbus.YzEventBus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * F4b 单测批(第 125 次):quotation 定稿/审核双线锁定守卫 + 定额聚合谓词回归(docs/15 R28)。
 * <ul>
 *   <li>锁定线:requireEditable 双谓词(status≠0 / attstatus≥2)统一 230003,
 *       lock/unlock/submit/audit/finish/setDefault 全守卫族;</li>
 *   <li>聚合谓词 ← 第 97 次「summary 把待审附加行误计入工费」产品缺陷回归锚:
 *       非附加行 status=0 + 附加行 status=2,其余(禁用/待审附加/驳回附加)一律不计。</li>
 * </ul>
 */
class QuotationLockLineTest extends KernelTestBase {

    private static final long ORG = 1L;
    private static final long OP = 1L;
    private static final long ORDER_ID = 701L;

    private QuotationService quotations;
    private NormService norms;

    @BeforeEach
    void setUp() {
        // 显式 id=1:类内多测试共享同库,delete 后 H2 自增不归零,服务侧按 id=1 取人(displayName/requireOrgUser)
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) values (1,?,?,?,?)",
                ORG, "op", "p", "运营");
        YzEventBus bus = new YzEventBus(jdbc, List.of(), 60, 3600, 5);
        quotations = new QuotationService(jdbc, STUB_MESSAGES, bus,
                new StubDecoration(), new StubQuota());
        norms = new NormService(jdbc, STUB_MESSAGES, bus);
    }

    private long quotation(int status, int attstatus) {
        jdbc.update("""
                insert into quotation_quotation(org_id, order_id, order_no, customer_name, house_label,
                    name, status, attstatus, created_by)
                values (?,?,?,?,?,?,?,?,?)
                """, ORG, ORDER_ID, "YZ0002", "李四", "万科城5-2-201",
                "全包方案" + System.nanoTime(), status, attstatus, OP);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    // ---- 锁定线 ----

    @Test
    void lockIsIdempotentOnTimeAndGuardsDoubleLock() {
        long id = quotation(0, 0);
        quotations.lock(ORG, id, OP);
        assertEquals(1, attOf(id, "status"));
        java.sql.Timestamp first = timeOf(id, "lock_time");
        assertNotNull(first);
        expectBiz(QuotationService.CODE_ALREADY_LOCKED, () -> quotations.lock(ORG, id, OP));
        quotations.unlock(ORG, id, OP);
        assertEquals(0, attOf(id, "status"));
        expectBiz(QuotationService.CODE_NOT_LOCKED_STATE, () -> quotations.unlock(ORG, id, OP));
    }

    @Test
    void editRejectedOnceLockedOrAudited() {
        long locked = quotation(0, 0);
        quotations.lock(ORG, locked, OP);
        expectBiz(QuotationService.CODE_LOCKED, () -> quotations.updatePricing(ORG, locked,
                new QuotationService.PricingCmd(null, null, null, null, null, null, null), OP));
        // 审核线推进(attstatus≥2)后编辑态同样冻结——锁定与审核正交双态位
        long audited = quotation(0, 2);
        expectBiz(QuotationService.CODE_LOCKED, () -> quotations.updatePricing(ORG, audited,
                new QuotationService.PricingCmd(null, null, null, null, null, null, null), OP));
    }

    @Test
    void repriceTimeGuards() {
        long id = quotation(0, 0);
        // 未来时点拒(第 84 次定案):语义校验而非仅形状
        expectBiz(QuotationService.CODE_REPRICE_TIME_INVALID,
                () -> quotations.reprice(ORG, id, "2999-01-01 00:00:00", OP));
        expectBiz(QuotationService.CODE_REPRICE_TIME_INVALID,
                () -> quotations.reprice(ORG, id, "not-a-time", OP));
        // 锁定后不可重算(requireEditable 统一闸)
        quotations.lock(ORG, id, OP);
        expectBiz(QuotationService.CODE_LOCKED, () -> quotations.reprice(ORG, id, null, OP));
    }

    // ---- 审核线:submit/audit/finish 全守卫族 ----

    @Test
    void auditLineStateFamily() {
        long id = quotation(0, 0);
        expectBiz(QuotationService.CODE_NOT_LOCKED, () -> quotations.submit(ORG, id, OP));
        quotations.lock(ORG, id, OP);
        quotations.submit(ORG, id, OP);
        assertEquals(1, attOf(id, "attstatus"));
        assertNotNull(timeOf(id, "submit_time"));
        expectBiz(QuotationService.CODE_ATT_SUBMITTED, () -> quotations.submit(ORG, id, OP));
        quotations.audit(ORG, id, OP);
        assertEquals(2, attOf(id, "attstatus"));
        expectBiz(QuotationService.CODE_ATT_NOT_SUBMITTED, () -> quotations.audit(ORG, id, OP));
        quotations.finish(ORG, id, OP);
        assertEquals(3, attOf(id, "attstatus"));
        expectBiz(QuotationService.CODE_ATT_FINISHED, () -> quotations.finish(ORG, id, OP));
        // 跳步:未审核(1)不可完结
        long skipped = quotation(0, 0);
        quotations.lock(ORG, skipped, OP);
        quotations.submit(ORG, skipped, OP);
        expectBiz(QuotationService.CODE_ATT_NOT_AUDITED, () -> quotations.finish(ORG, skipped, OP));
    }

    @Test
    void setDefaultRequiresLocked() {
        long editing = quotation(0, 0);
        expectBiz(QuotationService.CODE_NOT_LOCKABLE_DEFAULT, () -> quotations.setDefault(ORG, editing, OP));
        quotations.lock(ORG, editing, OP);
        quotations.setDefault(ORG, editing, OP);
        assertEquals(1, attOf(editing, "is_default"));
    }

    // ---- 定额聚合谓词(第 97 次缺陷回归锚) ----

    @Test
    void normSummaryCountsOnlyLiveRowsAndConfirmedAdditions() {
        // kind=1 人工:谓词四象限走遍(第 97 次缺陷回归锚)
        normItem(1, 0, 0, 0, 100000);   // 非附加+初始 → 计入
        normItem(1, 0, 1, 0, 200000);   // 非附加+禁用 → 不计
        normItem(1, 3, 0, 0, 300000);   // 附加+待审 → 不计(旧谓词误计,第 97 次修复)
        normItem(1, 3, 2, 0, 400000);   // 附加+确认 → 计入
        normItem(1, 3, 3, 0, 500000);   // 附加+驳回 → 不计
        normItem(1, 0, 0, 1, 50000);    // 非附加+初始+支向 → 负计
        Map<String, Object> labor = (Map<String, Object>) norms.summary(ORG, ORDER_ID).get("1");
        assertEquals(3, ((Number) labor.get("count")).intValue());
        assertEquals(450000L, ((Number) labor.get("laborFee")).longValue());
        // kind=0 材料:计数入,零工费
        normItem(0, 0, 0, 0, 0);
        Map<String, Object> material = (Map<String, Object>) norms.summary(ORG, ORDER_ID).get("0");
        assertEquals(1, ((Number) material.get("count")).intValue());
        assertEquals(0L, ((Number) material.get("laborFee")).longValue());
        // kind=2 工时:workdays 求和
        jdbc.update("""
                insert into quotation_norm_item(org_id, order_id, source_type, kind, name, workdays)
                values (?,?,0,2,'瓦工',80)
                """, ORG, ORDER_ID);
        Map<String, Object> time = (Map<String, Object>) norms.summary(ORG, ORDER_ID).get("2");
        assertEquals(80L, ((Number) time.get("workdays")).longValue());
    }

    private long normSeq = 0;

    private void normItem(int kind, int sourceType, int status, int atttype, long salary) {
        jdbc.update("""
                insert into quotation_norm_item(org_id, order_id, source_type, source_id, kind, name,
                    atttype, salary, trannum, status)
                values (?,?,?,?,?,?,?,?,?,?)
                """, ORG, ORDER_ID, sourceType, ++normSeq, kind, kind == 0 ? "材料" : "人工",
                atttype, salary, 0, status);
    }

    private int attOf(long quotationId, String column) {
        return jdbc.queryForObject("select " + column + " from quotation_quotation where id = ?",
                Integer.class, quotationId);
    }

    private java.sql.Timestamp timeOf(long quotationId, String column) {
        return jdbc.queryForObject("select " + column + " from quotation_quotation where id = ?",
                java.sql.Timestamp.class, quotationId);
    }

    // ---- 契约 stub ----

    static class StubDecoration implements DecorationApi {
        @Override
        public Optional<OrderSummary> queryOrder(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public Optional<CustomerRef> queryOrderCustomer(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public java.util.List<OrderBrief> listByCustomer(long orgId, long customerId) {
            return List.of();
        }

        @Override
        public long contractSignedTotalByMonth(long orgId, String yearMonth) {
            return 0;
        }

        @Override
        public long contractSignedTotalBetween(long orgId, String beginDay, String endDay) {
            return 0;
        }

        @Override
        public java.util.List<OrderTrackView> listOrderTrackViews(long orgId) {
            return List.of();
        }
    }

    static class StubQuota implements MaterialQuotaApi {
        @Override
        public Optional<PackageQuota> queryQuota(long orgId, long packageId, long goodsId) {
            return Optional.empty();
        }

        @Override
        public Optional<PackageRef> queryPackage(long orgId, long packageId) {
            return Optional.empty();
        }
    }
}
