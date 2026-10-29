package com.yingzao.addon.sales;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 月主材业绩指标 provider(D4 北极星):当月签单权责额(PRD 开放问题 2 推荐口径)——
 * 本域 SQL 聚合 sales_order(排除全退/倒单/废单),查询期直出 asOf=now,零新表;
 * 出货口径(备选)待 sales 域货物料聚合后再议。
 */
@Component
public class SalesMetricProvider implements MetricProvider {

    public static final String KEY = "sales.performance.materialMonthly";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcTemplate jdbc;

    public SalesMetricProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String metricKey() {
        return KEY;
    }

    @Override
    public MetricData fetch(MetricQuery query, MetricDefinition definition) {
        String ym = query.yearMonth() != null ? query.yearMonth()
                : (query.beginDay() != null ? query.beginDay().substring(0, 7)
                        : MONTH.format(LocalDate.now()));
        LocalDate begin = LocalDate.parse(ym + "-01");
        LocalDate next = begin.plusMonths(1);
        Long total = jdbc.queryForObject("""
                select coalesce(sum(total), 0) from sales_order
                where org_id = ? and status not in (2, 3, 4)
                  and created_at >= ? and created_at < ?
                """, Long.class, query.orgId(), java.sql.Date.valueOf(begin), java.sql.Date.valueOf(next));
        return MetricData.ok(KEY, java.time.LocalDateTime.now().toString(), definition.unit(),
                total == null ? 0L : total, MetricData.LEVEL_NORMAL);
    }
}
