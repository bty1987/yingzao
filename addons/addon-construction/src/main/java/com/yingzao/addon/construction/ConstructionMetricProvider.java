package com.yingzao.addon.construction;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 工单当日达成率指标 provider(D4 北极星):读工程维 ads 表(construction_ads_wo_task_daily,
 * V605 日刷链产出)——空态四分支实践:no_data=该日无应完工/当日未产出(不冒充 0%);
 * stale=批次时点距今超 26h(数据截至 X 日黄条,不以旧数冒充新数);level 按声明阈值
 * (98% 黄 / 95% 红,ads-reference 口径)。
 */
@Component
public class ConstructionMetricProvider implements MetricProvider {

    public static final String KEY = "construction.workload.dailyRate";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public ConstructionMetricProvider(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public String metricKey() {
        return KEY;
    }

    @Override
    public MetricData fetch(MetricQuery query, MetricDefinition definition) {
        // 月窗 → 日序列(近趋势);单日 → beginDay(缺省昨日,T+1 口径)
        if (query.yearMonth() != null) {
            List<MetricData.Point> points = new ArrayList<>();
            jdbc.queryForList("""
                    select stat_date, done_rate, due_cnt from construction_ads_wo_task_daily
                    where org_id = ? and stat_date like ? order by stat_date
                    """, query.orgId(), query.yearMonth() + "%").forEach(row -> {
                if (((Number) row.get("due_cnt")).intValue() > 0) {
                    points.add(new MetricData.Point(String.valueOf(row.get("stat_date")),
                            ((Number) row.get("done_rate")).longValue()));
                }
            });
            return MetricData.series(KEY, LocalDateTime.now().toString(), definition.unit(), points);
        }
        String day = query.beginDay() != null ? query.beginDay() : DAY.format(LocalDate.now().minusDays(1));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select due_cnt, done_cnt, done_rate, batch_time from construction_ads_wo_task_daily
                where org_id = ? and stat_date = ?
                """, query.orgId(), java.sql.Date.valueOf(LocalDate.parse(day)));
        if (rows.isEmpty()) {
            return MetricData.noData(KEY, msg("construction.metric.noData"));
        }
        Map<String, Object> row = rows.get(0);
        int due = ((Number) row.get("due_cnt")).intValue();
        if (due == 0) {
            return MetricData.noData(KEY, msg("construction.metric.noDue"));
        }
        long rate = ((Number) row.get("done_rate")).longValue();
        // datetime 列经驱动可返回 LocalDateTime 或 Timestamp(连接器版本差异),通用取值
        LocalDateTime batchAt;
        Object rawBatch = row.get("batch_time");
        if (rawBatch instanceof LocalDateTime dt) {
            batchAt = dt;
        } else if (rawBatch instanceof java.sql.Timestamp ts) {
            batchAt = ts.toLocalDateTime();
        } else {
            batchAt = LocalDateTime.now();
        }
        String asOf = batchAt.toString();
        // 批次时点距今超 26h = 日刷断链(应 T+1 03:00),黄条不以旧数冒充
        if (batchAt.isBefore(LocalDateTime.now().minusHours(26))) {
            return MetricData.stale(KEY, asOf, msg("construction.metric.stale"));
        }
        int level = MetricData.levelOf(rate, definition.thresholdWarn(), definition.thresholdCritical(),
                true);
        return MetricData.ok(KEY, asOf, definition.unit(), rate, level);
    }
}
