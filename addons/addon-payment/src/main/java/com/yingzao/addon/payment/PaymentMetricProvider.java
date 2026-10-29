package com.yingzao.addon.payment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 月经营性回款指标 provider(D4 北极星):收款明细按月聚合(收付/认领到账口径)——
 * payment_receipt direction=0 按 collect_time 归月 SUM(amount),查询期直出 asOf=now,
 * 零新表;财务维时间窗恒「月」(docs/06 §3.5)。V1 含全部收款类型,不含退款方向。
 */
@Component
public class PaymentMetricProvider implements MetricProvider {

    public static final String KEY = "payment.collection.operatingMonthly";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcTemplate jdbc;

    public PaymentMetricProvider(JdbcTemplate jdbc) {
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
                select coalesce(sum(amount), 0) from payment_receipt
                where org_id = ? and direction = 0
                  and collect_time >= ? and collect_time < ?
                """, Long.class, query.orgId(), java.sql.Timestamp.valueOf(begin.atStartOfDay()),
                java.sql.Timestamp.valueOf(next.atStartOfDay()));
        return MetricData.ok(KEY, java.time.LocalDateTime.now().toString(), definition.unit(),
                total == null ? 0L : total, MetricData.LEVEL_NORMAL);
    }
}
