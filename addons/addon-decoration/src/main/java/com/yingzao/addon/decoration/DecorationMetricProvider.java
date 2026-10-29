package com.yingzao.addon.decoration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 月签约合同额指标 provider(D4 北极星):包装 DecorationApi 契约
 * contractSignedTotalByMonth(当月首签单的签约拆分款应收合计,第 73 次为 target 建的
 * 唯一口径)——target/PK/驾驶舱三消费方同源,禁另写 SQL(docs/06 §2.3 唯一口径对账)。
 * 契约查询期直出,asOf=now。
 */
@Component
public class DecorationMetricProvider implements MetricProvider {

    public static final String KEY = "decoration.contract.signedMonthly";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DecorationApi api;

    public DecorationMetricProvider(DecorationApi api) {
        this.api = api;
    }

    @Override
    public String metricKey() {
        return KEY;
    }

    @Override
    public MetricData fetch(MetricQuery query, MetricDefinition definition) {
        // 月指标:yearMonth 优先;仅给区间时取起日所在月(宽松兜底,契约口径按月)
        String ym = query.yearMonth() != null ? query.yearMonth()
                : (query.beginDay() != null ? query.beginDay().substring(0, 7)
                        : MONTH.format(LocalDate.now()));
        long total = api.contractSignedTotalByMonth(query.orgId(), ym);
        return MetricData.ok(KEY, java.time.LocalDateTime.now().toString(), definition.unit(), total,
                MetricData.LEVEL_NORMAL);
    }
}
