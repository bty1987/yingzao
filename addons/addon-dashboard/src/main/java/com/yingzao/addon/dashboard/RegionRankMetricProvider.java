package com.yingzao.addon.dashboard;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.org.OrganizationService;
import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 大区签约额排名 provider(dashboard 自有指标):经内核 organization 直接下级(大区)
 * 逐个走 DecorationApi 契约聚合当月签约额,值降序——签约额唯一口径走契约
 * (docs/06 §2.3 唯一口径对账,target/PK/驾驶舱三消费方同源)。
 *
 * <p>诚实边界:业务单据归属=公司级,大区子树分母可建而分子恒 0(单据挂根组织),
 * 排名 V1 仅在单据归属模型落地后点亮分区维度;DecorationApi 未启用时降级 no_data。
 */
@Component
public class RegionRankMetricProvider implements MetricProvider {

    public static final String KEY = "dashboard.regionSignRank";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final OrganizationService orgs;
    private final ObjectProvider<DecorationApi> decorationApi;

    public RegionRankMetricProvider(OrganizationService orgs, ObjectProvider<DecorationApi> decorationApi) {
        this.orgs = orgs;
        this.decorationApi = decorationApi;
    }

    @Override
    public String metricKey() {
        return KEY;
    }

    @Override
    public MetricData fetch(MetricQuery query, MetricDefinition definition) {
        DecorationApi api = decorationApi.getIfAvailable();
        if (api == null) {
            return MetricData.noData(KEY, "addon-decoration 未启用,无签约额数据源");
        }
        String ym = query.yearMonth() != null ? query.yearMonth() : MONTH.format(LocalDate.now());
        List<MetricData.Point> points = new ArrayList<>();
        for (Map<String, Object> child : orgs.childrenOf(query.orgId())) {
            long childId = ((Number) child.get("id")).longValue();
            String name = String.valueOf(child.get("name"));
            long signed = api.contractSignedTotalByMonth(childId, ym);
            points.add(new MetricData.Point(name, signed));
        }
        points.sort(Comparator.comparingLong(MetricData.Point::value).reversed());
        return MetricData.series(KEY, java.time.LocalDateTime.now().toString(), definition.unit(), points);
    }
}
