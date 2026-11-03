package com.yingzao.addon.dashboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.QuotationApi;
import com.yingzao.addons.api.SalesApi;
import com.yingzao.addons.api.TargetApi;
import com.yingzao.core.org.OrgScopeService;

/**
 * 主材销售工作台聚合服务(W2,docs/prd/addon-dashboard-workbench.md §三.3):
 * 全只读聚合——五阶段漏斗/跟单明细/异常预警/月度业绩达成(目标经 TargetApi 契约、
 * 实绩经 SalesApi 契约子树求和)/目标拆分视图,零跨域直查零新表。
 *
 * <p>阶段判定复用 W1 纯函数 {@link MaterialStageDeriver};停留天数锚点按阶段取各域
 * 时间戳(①登记/②首签/③最近状态变更/④完工/⑤完工→决算),无锚回落单据登记日
 * (PRD §三.1 V1 诚实边界;③工程开工无原生列,以 updated_at 近似——任何无关更新会
 * 低估停留,随 V2 换原生锚)。
 */
@Service
public class MaterialWorkbenchService {

    /** 逾期跟进阈值(V1 常量,行业中性;组织级可配随 V2)。 */
    public static final int DWELL_THRESHOLD_DAYS = 30;

    private static final Pattern YM = Pattern.compile("\\d{4}-\\d{2}");
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String LINE_MATERIAL = "material";

    private final DecorationApi decorations;
    private final QuotationApi quotations;
    private final SalesApi sales;
    private final TargetApi targets;
    private final OrgScopeService orgScopes;
    private final JdbcTemplate jdbc;

    public MaterialWorkbenchService(DecorationApi decorations, QuotationApi quotations,
            SalesApi sales, TargetApi targets, OrgScopeService orgScopes, JdbcTemplate jdbc) {
        this.decorations = decorations;
        this.quotations = quotations;
        this.sales = sales;
        this.targets = targets;
        this.orgScopes = orgScopes;
        this.jdbc = jdbc;
    }

    /** 跟单行:阶段判定+停留天数+人员名(聚合中间态,summary/tracking 共用)。 */
    record TrackRow(DecorationApi.OrderTrackView view, int stage, long dwellDays,
                    String designer, String salesman) {
    }

    /** 月度汇总体:KPI 达成卡+跟单户数+五阶段漏斗+异常预警+目标拆分视图。 */
    public Map<String, Object> summary(long orgId, String yearMonth, long node) {
        List<TrackRow> rows = trackRows(orgId, null, null);

        // KPI:目标(TargetApi 契约)/实绩(SalesApi 契约子树求和)/缺口/达成率/时间进度/落后
        long actual = 0;
        for (Long org : orgScopes.scopeOrgIds(node)) {
            actual += sales.materialSalesTotalByMonth(org, yearMonth);
        }
        Map<String, Object> kpi = new LinkedHashMap<>();
        var plan = targets.planOfMonth(orgId, node, yearMonth, LINE_MATERIAL);
        boolean hasTarget = plan.isPresent();
        long target = plan.map(TargetApi.PlanView::amount).orElse(0L);
        Long ratePpm = hasTarget && target > 0 ? Math.min(actual * 10000 / target, 999999L) : null;
        long timeProgressPpm = timeProgressPpm(yearMonth);
        kpi.put("hasTarget", hasTarget);
        kpi.put("target", hasTarget ? target : null);
        kpi.put("actual", actual);
        kpi.put("gap", hasTarget ? Math.max(target - actual, 0) : null);
        kpi.put("ratePpm", ratePpm);
        kpi.put("timeProgressPpm", timeProgressPpm);
        kpi.put("lagging", hasTarget && ratePpm != null && ratePpm < timeProgressPpm);

        // 跟单户数 + 五阶段漏斗 + 异常预警
        Map<Integer, Integer> byStage = new HashMap<>();
        int overdue = 0;
        for (TrackRow r : rows) {
            byStage.merge(r.stage(), 1, Integer::sum);
            if (r.stage() >= MaterialStageDeriver.STAGE_QUOTING
                    && r.stage() <= MaterialStageDeriver.STAGE_SETTLING
                    && r.dwellDays() > DWELL_THRESHOLD_DAYS) {
                overdue++;
            }
        }
        int tracked = 0;
        for (int s = 1; s <= 5; s++) {
            tracked += byStage.getOrDefault(s, 0);
        }
        List<Map<String, Object>> funnel = new ArrayList<>();
        for (int s = 1; s <= 5; s++) {
            funnel.add(Map.of("stage", s, "count", byStage.getOrDefault(s, 0)));
        }
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("overdue", overdue);
        alerts.put("dwellThresholdDays", DWELL_THRESHOLD_DAYS);
        alerts.put("targetLagging", kpi.get("lagging"));

        // 目标拆分视图(逐节点目标+子树实绩;停用目标带 status 由前端呈现)
        List<Map<String, Object>> plans = new ArrayList<>();
        for (TargetApi.PlanView p : targets.listPlansOfMonth(orgId, yearMonth, LINE_MATERIAL)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planId", p.planId());
            row.put("nodeId", p.orgNodeId());
            row.put("nodeName", p.orgNodeName());
            row.put("amount", p.amount());
            row.put("status", p.status());
            if (p.status() == 0) {
                long nodeActual = 0;
                for (Long org : orgScopes.scopeOrgIds(p.orgNodeId())) {
                    nodeActual += sales.materialSalesTotalByMonth(org, yearMonth);
                }
                row.put("actual", nodeActual);
                row.put("ratePpm", p.amount() == 0 ? null
                        : Math.min(nodeActual * 10000 / p.amount(), 999999L));
            }
            plans.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", yearMonth);
        out.put("node", node);
        out.put("nodes", orgNodes());
        out.put("kpi", kpi);
        out.put("tracked", Map.of("total", tracked,
                "nearSign", byStage.getOrDefault(MaterialStageDeriver.STAGE_SIGNED, 0)));
        out.put("funnel", funnel);
        out.put("alerts", alerts);
        out.put("plans", plans);
        return out;
    }

    /** 跟单明细(分页;stage/keyword 过滤;stage 升序+停留降序=先急后缓)。 */
    public Map<String, Object> tracking(long orgId, Long node, Integer stage,
            String keyword, int page, int size) {
        List<TrackRow> rows = trackRows(orgId, node, stage);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            rows = rows.stream().filter(r -> contains(r, kw)).toList();
        }
        rows = rows.stream()
                .sorted(Comparator.comparingInt(TrackRow::stage)
                        .thenComparing(Comparator.comparingLong(TrackRow::dwellDays).reversed()))
                .toList();
        int from = Math.max((page - 1) * size, 0);
        int to = Math.min(from + size, rows.size());
        List<Map<String, Object>> pageRows = new ArrayList<>();
        for (TrackRow r : rows.subList(from, to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", r.view().orderId());
            row.put("orderNo", r.view().orderNo());
            row.put("customerName", r.view().customerName());
            row.put("houseLabel", r.view().houseLabel());
            row.put("stage", r.stage());
            row.put("dwellDays", r.dwellDays());
            row.put("designer", r.designer());
            row.put("salesman", r.salesman());
            row.put("createdAt", r.view().createdAt());
            pageRows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("page", page);
        out.put("size", size);
        out.put("rows", pageRows);
        return out;
    }

    private boolean contains(TrackRow r, String kw) {
        return contains(r.view().orderNo(), kw) || contains(r.view().customerName(), kw)
                || contains(r.view().houseLabel(), kw);
    }

    private boolean contains(String s, String kw) {
        return s != null && s.toLowerCase().contains(kw);
    }

    /** 全量跟单行:契约拼装判定输入→纯函数判定→停留锚点;节点过滤经人员组织归属。 */
    private List<TrackRow> trackRows(long orgId, Long node, Integer stage) {
        Set<Long> activeQuotes = quotations.activeQuoteOrderIds(orgId);
        Map<Long, SalesApi.OrderSalesProgress> progress = new HashMap<>();
        for (SalesApi.OrderSalesProgress p : sales.listOrderProgress(orgId)) {
            progress.put(p.orderId(), p);
        }
        Set<Long> scopeUsers = node == null ? null : usersInScope(node);
        Map<Long, String> userNames = userNames();
        List<TrackRow> rows = new ArrayList<>();
        for (DecorationApi.OrderTrackView v : decorations.listOrderTrackViews(orgId)) {
            if (scopeUsers != null && !scopeUsers.contains(v.designerId())
                    && !scopeUsers.contains(v.salesmanId())) {
                continue;
            }
            SalesApi.OrderSalesProgress p = progress.get(v.orderId());
            int st = MaterialStageDeriver.derive(new MaterialStageDeriver.StageInput(
                    v.status(), v.projectStatus(), activeQuotes.contains(v.orderId()),
                    p == null ? 0 : p.total(), p == null ? 0 : p.finished()));
            if (st == MaterialStageDeriver.STAGE_NONE
                    || (stage != null && stage > 0 && st != stage)) {
                continue;
            }
            LocalDate today = LocalDate.now();
            long dwell = dwellDays(st, v, today);
            rows.add(new TrackRow(v, st, dwell,
                    userNames.get(v.designerId()), userNames.get(v.salesmanId())));
        }
        return rows;
    }

    /** 停留天数锚点:①登记/②首签/③最近状态变更/④⑤完工→决算,均回落登记日(PRD §三.1)。 */
    static long dwellDays(int stage, DecorationApi.OrderTrackView v, LocalDate today) {
        String anchor = switch (stage) {
            case MaterialStageDeriver.STAGE_SIGNED -> v.contractTime();
            case MaterialStageDeriver.STAGE_BUILDING, MaterialStageDeriver.STAGE_SETTLING -> v.finishTime();
            case MaterialStageDeriver.STAGE_DONE -> v.finishTime() != null ? v.finishTime() : v.accountTime();
            default -> null;
        };
        if (anchor == null) {
            anchor = MaterialStageDeriver.STAGE_BUILDING == stage
                    || MaterialStageDeriver.STAGE_SETTLING == stage ? v.updatedAt() : v.createdAt();
        }
        if (anchor == null) {
            anchor = v.createdAt();
        }
        if (anchor == null) {
            return 0;
        }
        LocalDate d = LocalDate.parse(anchor);
        return Math.max(java.time.temporal.ChronoUnit.DAYS.between(d, today), 0);
    }

    /** 时间进度(万分比):当月已过天/全月天;非当月=整月(历史或计划月恒满格)。 */
    private long timeProgressPpm(String yearMonth) {
        YearMonth m = YearMonth.parse(yearMonth, YM_FMT);
        LocalDate now = LocalDate.now();
        if (!YM_FMT.format(now).equals(yearMonth)) {
            return 10000;
        }
        return Math.min((long) now.getDayOfMonth() * 10000 / m.lengthOfMonth(), 10000);
    }

    /** 组织节点平铺(选择器数据源;启用节点)。 */
    private List<Map<String, Object>> orgNodes() {
        return jdbc.queryForList("""
                select id, parent_id, name, level_type from organization
                where status = 0 order by id
                """).stream().map(r -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("id", ((Number) r.get("id")).longValue());
                    Object pid = r.get("parent_id");
                    n.put("parentId", pid == null ? null : ((Number) pid).longValue());
                    n.put("name", r.get("name"));
                    n.put("levelType", r.get("level_type"));
                    return n;
                }).toList();
    }

    /** 节点子树内的用户 id 集(跟单行按设计师/业务员归属过滤)。 */
    private Set<Long> usersInScope(long node) {
        Set<Long> scope = new HashSet<>(orgScopes.scopeOrgIds(node));
        Set<Long> users = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, org_id from yz_user where status = 0")) {
            Object org = r.get("org_id");
            if (org != null && scope.contains(((Number) org).longValue())) {
                users.add(((Number) r.get("id")).longValue());
            }
        }
        return users;
    }

    /** 全量用户名映射(批查一次;跟单行人员名投影;yz_user 展示列=display_name)。 */
    private Map<Long, String> userNames() {
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, display_name from yz_user")) {
            names.put(((Number) r.get("id")).longValue(), (String) r.get("display_name"));
        }
        return names;
    }
}
