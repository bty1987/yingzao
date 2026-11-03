package com.yingzao.addon.target;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.MarketApi;
import com.yingzao.addons.api.SalesApi;
import com.yingzao.core.KernelTestBase;
import com.yingzao.core.org.OrganizationService;
import com.yingzao.core.org.OrganizationService.CreateBody;
import com.yingzao.core.org.OrgScopeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * target 主材业绩线单测(W1 工作台族,docs/prd/addon-dashboard-workbench.md):
 * line 白名单三线/实绩经 SalesApi 契约(stub 不碰聚合 SQL——聚合正确性由
 * addon-sales 冒烟覆盖)/子树聚合契约在 material 线下不变。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetMaterialLineTest extends KernelTestBase {

    static class StubDecoration implements DecorationApi {
        long signedMonth;

        @Override
        public Optional<OrderSummary> queryOrder(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public Optional<CustomerRef> queryOrderCustomer(long orgId, long orderId) {
            return Optional.empty();
        }

        @Override
        public long contractSignedTotalByMonth(long orgId, String yearMonth) {
            return signedMonth;
        }

        @Override
        public long contractSignedTotalBetween(long orgId, String beginDay, String endDay) {
            return 0;
        }

        @Override
        public List<OrderBrief> listByCustomer(long orgId, long customerId) {
            return List.of();
        }

        @Override
        public List<OrderTrackView> listOrderTrackViews(long orgId) {
            return List.of();
        }
    }

    static class StubMarket implements MarketApi {
        @Override
        public boolean beginAccept(long inboundId, long orgId) {
            return false;
        }

        @Override
        public void acceptResult(long inboundId, long orgId, boolean success,
                Long leadId, Long chanceId, String note) {
        }

        @Override
        public long inboundTotalByMonth(long orgId, String yearMonth) {
            return 0;
        }

        @Override
        public long inboundTotalBetween(long orgId, String beginDay, String endDay) {
            return 0;
        }

        @Override
        public CasePage cCases(long orgId, String city, int page, int size) {
            return new CasePage(0, List.of());
        }

        @Override
        public String revealPhone(long inboundId, long orgId, long viewerId) {
            return "";
        }
    }

    static class StubSales implements SalesApi {
        long materialMonth;
        long materialBetween;

        @Override
        public long collectedRatio(long orgId, long orderId) {
            return 0;
        }

        @Override
        public Optional<DecorationApi.CustomerRef> queryOrderCustomer(long orgId, long salesOrderId) {
            return Optional.empty();
        }

        @Override
        public Optional<SalesOrderRef> querySalesOrder(long orgId, long salesOrderId) {
            return Optional.empty();
        }

        @Override
        public int countUnfinishedOrders(long orgId, long orderId) {
            return 0;
        }

        @Override
        public List<SalesOrderRef> listByDecorationOrder(long orgId, long orderId) {
            return List.of();
        }

        @Override
        public long materialSalesTotalByMonth(long orgId, String yearMonth) {
            return materialMonth;
        }

        @Override
        public long materialSalesTotalBetween(long orgId, String beginDay, String endDay) {
            return materialBetween;
        }

        @Override
        public List<OrderSalesProgress> listOrderProgress(long orgId) {
            return List.of();
        }
    }

    private OrganizationService orgs;
    private StubDecoration deco;
    private StubSales sales;
    private TargetService targets;
    private long childNode;

    @BeforeEach
    void setUp() {
        // 基类 @BeforeEach 先清表(organization 在清表列),树随用例重建
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        childNode = orgs.create(1, new CreateBody(1L, "烟测分区", "region"), 1);
        deco = new StubDecoration();
        sales = new StubSales();
        targets = new TargetService(jdbc, STUB_MESSAGES, deco, new StubMarket(), sales,
                new OrgScopeService(orgs));
        jdbc.update("delete from target_plan");
    }

    private long createTarget(long node, String line, long amount, String ym) {
        return targets.create(1, new TargetService.CreateCmd(node, ym, amount, null, line), 1L);
    }

    @Test
    void materialLineAcceptedAndAchievedViaContract() {
        long id = createTarget(childNode, TargetService.LINE_MATERIAL, 10000000L, "2026-11");
        sales.materialMonth = 3200000000L; // stub 契约返回值直接决定实绩
        Map<String, Object> ach = targets.achievement(1, childNode, "material", "2026-11");
        assertEquals("material", ach.get("line"));
        assertEquals(10000000L, ((Number) ach.get("target")).longValue());
        assertEquals(3200000000L, ((Number) ach.get("actual")).longValue());
        assertTrue(id > 0);
    }

    @Test
    void contractLineUnaffectedByMaterialStub() {
        createTarget(childNode, TargetService.LINE_CONTRACT, 5000000L, "2026-11");
        sales.materialMonth = 999999999L; // material 值不得串线
        deco.signedMonth = 123000000L;
        Map<String, Object> ach = targets.achievement(1, childNode, "contract", "2026-11");
        assertEquals(123000000L, ((Number) ach.get("actual")).longValue());
    }

    @Test
    void unknownLineRejected() {
        expectBiz(TargetService.CODE_PARAM,
                () -> createTarget(childNode, "bogus", 1000L, "2026-11"));
    }

    @Test
    void subtreeAggregationSumsMaterial() {
        orgs.create(1, new CreateBody(childNode, "烟测部门", "department"), 1);
        createTarget(childNode, "material", 1L, "2026-12");
        sales.materialMonth = 7000000L; // 子树两节点同 stub 值 → Σ=2×
        Map<String, Object> ach = targets.achievement(1, childNode, "material", "2026-12");
        assertEquals(14000000L, ((Number) ach.get("actual")).longValue());
    }

    @Test
    void stageBudgetSumsRemainIndependent() {
        // 阶段(区间切片)在 material 线走 materialSalesTotalBetween stub
        long id = createTarget(childNode, "material", 10000000L, "2026-11");
        sales.materialBetween = 2500000L;
        targets.createStage(1, id,
                new TargetService.StageCmd("s1", 1, "2026-11-01", "2026-11-10", 4000000L, null), 1L);
        List<Map<String, Object>> rows = targets.stages(1, id);
        assertEquals(1, rows.size());
        assertEquals(2500000L, ((Number) rows.get(0).get("actual")).longValue());
    }
}
