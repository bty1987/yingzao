package com.yingzao.addon.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.GoodsApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.core.KernelTestBase;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.notification.NotificationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4b 单测批(第 125 次):sales 双轴(fullpay_status/deposit_status)+定金门控回归(docs/15 R28)。
 * 关键行为锚定:
 * <ul>
 *   <li>双轴分流与条件 UPDATE 幂等 ← 第 65 次 V1102 定金款线(事件重放零漂移);</li>
 *   <li>退定金轴回「已录」+重建定金款幂等锚 ← 第 67 次闭环(组内无未收定金单才建);</li>
 *   <li>拆线(resyncBill)deposit&gt;0 两单/deposit=0 单单 ← 第 65 次(V1 行为保持);</li>
 *   <li>完结双线对称守卫 300002 ← 第 65 次(尾款先行确认放行属老尾款语义);</li>
 *   <li>定金顺序门控 270010 实现在 addon-payment collect(见 PaymentBillLifecycleTest)。</li>
 * </ul>
 */
class SalesDualAxisTest extends KernelTestBase {

    private static final long ORG = 1L;
    private static final long ORDER_ID = 601L;

    private StubPayment payments;
    private SalesOrderService sales;

    @BeforeEach
    void setUp() {
        // 显式 id=1:类内多测试共享同库,delete 后 H2 自增不归零,服务侧按 id=1 取人(displayName/requireOrgUser)
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) values (1,?,?,?,?)",
                ORG, "op", "p", "运营");
        YzEventBus bus = new YzEventBus(jdbc, List.of(), 60, 3600, 5);
        payments = new StubPayment();
        StubDecoration decorations = new StubDecoration();
        StubGoods goods = new StubGoods();
        NotificationService notifications = new NotificationService(jdbc, STUB_MESSAGES);
        SalesDeliveryFlowService flows = new SalesDeliveryFlowService(jdbc, STUB_MESSAGES, null) {
            @Override
            public void backfillForSaleBrand(long orgId, long saleId, long brandId) {
                // V1104 里程碑补扫不在本批回归面,单测 no-op
            }
        };
        sales = new SalesOrderService(jdbc, STUB_MESSAGES, bus, decorations, goods, payments,
                notifications, flows);
    }

    private long sale(long total, long deposit, int fullpayStatus, int depositStatus, int balanceStatus) {
        jdbc.update("""
                insert into sales_order(org_id, code, order_id, order_no, customer_name, house_label,
                    status, total, deposit_amount, fullpay_status, deposit_status, balance_status,
                    created_by, created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, ORG, "XSTEST" + System.nanoTime(), ORDER_ID, "YZ0001", "张三", "恒大绿洲3-1-101",
                0, total, deposit, fullpayStatus, depositStatus, balanceStatus, 1L, "运营");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void item(long saleId, long brandId, String brandName, long total) {
        jdbc.update("""
                insert into sales_order_item(org_id, sale_id, package_id, goods_id, goods_name,
                    brand_id, brand_name, num, selling, total)
                values (?,?,0,0,'瓷砖',?,?,1000,?,?)
                """, ORG, saleId, brandId, brandName, total, total);
    }

    // ---- 收款事件:双轴分流 + 条件 UPDATE 幂等 ----

    @Test
    void collectedEventRoutesToDepositAxisOnly() {
        long id = sale(500000, 100000, -1, -1, 0);
        sales.onPaymentCollected(ORG, id, "sales_deposit");
        assertEquals(1, axis(id, "deposit_status"));
        assertEquals(-1, axis(id, "fullpay_status"));
        // 事件重放:条件 UPDATE 幂等,仍为已付
        sales.onPaymentCollected(ORG, id, "sales_deposit");
        assertEquals(1, axis(id, "deposit_status"));
    }

    @Test
    void collectedEventRoutesToFullpayAxisOnly() {
        long id = sale(500000, 100000, -1, -1, 0);
        sales.onPaymentCollected(ORG, id, "sales_full");
        assertEquals(1, axis(id, "fullpay_status"));
        assertEquals(-1, axis(id, "deposit_status"));
    }

    @Test
    void confirmedEventUnlocksSettlementPerBrand() {
        long id = sale(500000, 100000, -1, 1, 0);
        item(id, 7L, "马可波罗", 300000);
        item(id, 8L, "诺贝尔", 200000);
        sales.onPaymentConfirmed(ORG, id, "sales_deposit", 1L);
        assertEquals(2, axis(id, "deposit_status"));
        assertEquals(1, axis(id, "balance_status"));
        Integer settleRows = jdbc.queryForObject(
                "select count(*) from sales_settlement where org_id = ? and sale_id = ?", Integer.class, ORG, id);
        assertEquals(2, settleRows);
        Long brand7 = jdbc.queryForObject(
                "select total from sales_settlement where org_id = ? and sale_id = ? and brand_id = 7",
                Long.class, ORG, id);
        assertEquals(Long.valueOf(300000L), brand7);
        // 事件重放:updated==0 提前返回,结算行不重复生成
        sales.onPaymentConfirmed(ORG, id, "sales_deposit", 1L);
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from sales_settlement where org_id = ? and sale_id = ?", Integer.class, ORG, id));
    }

    // ---- 退定金闭环(第 67 次):轴回已录 + 重建定金款幂等锚 ----

    @Test
    void depositRefundRewindsAxisAndRebuildsBill() {
        long id = sale(500000, 100000, 0, 2, 0);
        payments.billList = List.of();
        sales.onPaymentRefunded(ORG, id, "sales_deposit");
        assertEquals(0, axis(id, "deposit_status"));
        assertEquals(1, payments.created.size());
        assertEquals("sales_deposit", payments.created.get(0).typeCode());
        assertEquals(100000L, payments.created.get(0).amount());
    }

    @Test
    void depositRefundReplayDoesNotDuplicateBill() {
        long id = sale(500000, 100000, 0, 2, 0);
        // 幂等锚:组内存在未收定金单 → 不再重建
        payments.billList = List.of(new PaymentApi.BillSummary(901L, "sales_deposit", "定金",
                0, 100000, 2, 0));
        sales.onPaymentRefunded(ORG, id, "sales_deposit");
        assertTrue(payments.created.isEmpty());
    }

    // ---- 拆线(generateBill→resyncBill):deposit>0 两单 / deposit=0 单单 ----

    @Test
    void generateBillSplitsDepositAndFull() {
        long id = sale(500000, 150000, -1, -1, 0);
        payments.billList = List.of();
        sales.generateBill(ORG, id, 1L);
        assertEquals(2, payments.created.size());
        assertEquals("sales_deposit", payments.created.get(0).typeCode());
        assertEquals(150000L, payments.created.get(0).amount());
        assertEquals("sales_full", payments.created.get(1).typeCode());
        assertEquals(350000L, payments.created.get(1).amount());
        assertEquals(0, axis(id, "fullpay_status"));
        assertEquals(0, axis(id, "deposit_status"));
    }

    @Test
    void generateBillKeepsSingleFullLineWhenNoDeposit() {
        long id = sale(500000, 0, -1, -1, 0);
        payments.billList = List.of();
        sales.generateBill(ORG, id, 1L);
        assertEquals(1, payments.created.size());
        assertEquals("sales_full", payments.created.get(0).typeCode());
        assertEquals(500000L, payments.created.get(0).amount());
        assertEquals(0, axis(id, "fullpay_status"));
        // deposit=0:定金轴保持未录(V1 行为)
        assertEquals(-1, axis(id, "deposit_status"));
    }

    @Test
    void generateBillResyncCancelsUnpaidBillsFirst() {
        long id = sale(500000, 150000, 0, 0, 0);
        payments.billList = List.of(new PaymentApi.BillSummary(902L, "sales_deposit", "定金",
                0, 100000, 2, 0));
        sales.generateBill(ORG, id, 1L);
        assertEquals(List.of(902L), payments.canceled);
        assertEquals(2, payments.created.size());
    }

    // ---- 完结/废单:双轴对称守卫 300002 ----

    @Test
    void finishRequiresFullpayConfirmed() {
        long id = sale(500000, 0, 1, -1, 0);
        expectBiz(SalesOrderService.CODE_BAD_STATE, () -> sales.finish(ORG, id, 1L));
    }

    @Test
    void finishRequiresDepositConfirmedWhenSplit() {
        long id = sale(500000, 100000, 2, 1, 0);
        expectBiz(SalesOrderService.CODE_BAD_STATE, () -> sales.finish(ORG, id, 1L));
        jdbc.update("update sales_order set deposit_status = 2 where id = ?", id);
        sales.finish(ORG, id, 1L);
        assertEquals(5, axis(id, "status"));
    }

    @Test
    void finishRejectsWhileAnyDeliveryPending() {
        long id = sale(500000, 0, 2, -1, 0);
        item(id, 7L, "马可波罗", 500000);
        long itemId = jdbc.queryForObject("select id from sales_order_item where sale_id = ?", Long.class, id);
        jdbc.update("insert into sales_delivery_item(org_id, sale_item_id, status) values (?,?,1)", ORG, itemId);
        expectBiz(SalesOrderService.CODE_BAD_STATE, () -> sales.finish(ORG, id, 1L));
        jdbc.update("update sales_delivery_item set status = 4 where sale_item_id = ?", itemId);
        sales.finish(ORG, id, 1L);
        assertEquals(5, axis(id, "status"));
    }

    @Test
    void terminateGuardsAndResetsDualAxis() {
        long blocked = sale(500000, 100000, 0, 1, 0);
        expectBiz(SalesOrderService.CODE_BAD_STATE, () -> sales.terminate(ORG, blocked, 1L));
        long settled = sale(500000, 0, -1, -1, 1);
        expectBiz(SalesOrderService.CODE_BAD_STATE, () -> sales.terminate(ORG, settled, 1L));
        long clean = sale(500000, 100000, 0, 0, 0);
        payments.billList = List.of(new PaymentApi.BillSummary(903L, "sales_deposit", "定金",
                0, 100000, 2, 0));
        sales.terminate(ORG, clean, 1L);
        assertEquals(4, axis(clean, "status"));
        assertEquals(-1, axis(clean, "fullpay_status"));
        assertEquals(-1, axis(clean, "deposit_status"));
        assertEquals(List.of(903L), payments.canceled);
    }

    private int axis(long saleId, String column) {
        return jdbc.queryForObject("select " + column + " from sales_order where id = ?", Integer.class, saleId);
    }

    // ---- 契约 stub(按接口拆独立类,W1 教训:单类实现多胖接口必脆) ----

    static class StubPayment implements PaymentApi {
        record CreatedBill(String typeCode, long amount) {
        }

        final List<CreatedBill> created = new ArrayList<>();
        final List<Long> canceled = new ArrayList<>();
        List<BillSummary> billList = List.of();

        @Override
        public long createBill(CreateBillCmd cmd) {
            created.add(new CreatedBill(cmd.typeCode(), cmd.amount()));
            return created.size();
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

    static class StubDecoration implements DecorationApi {
        @Override
        public Optional<OrderSummary> queryOrder(long orgId, long orderId) {
            return Optional.of(new OrderSummary(ORDER_ID, "YZ0001", "张三", "恒大绿洲3-1-101", 0, 3, 0, 8L, 9L));
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

    static class StubGoods implements GoodsApi {
        @Override
        public Optional<GoodsSummary> queryGoods(long orgId, long goodsId) {
            return Optional.of(new GoodsSummary(goodsId, "G1", "瓷砖", "片", 0, 100000, 0, 7L, "马可波罗"));
        }

        @Override
        public Optional<SkuAttrSummary> querySkuAttr(long orgId, long goodsId, long attrId) {
            return Optional.empty();
        }

        @Override
        public java.util.List<PackageItem> listPackageItems(long orgId, long packageGoodsId) {
            return List.of();
        }
    }
}
