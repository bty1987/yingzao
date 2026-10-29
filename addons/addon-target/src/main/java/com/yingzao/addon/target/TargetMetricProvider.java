package com.yingzao.addon.target;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricProvider;
import com.yingzao.core.report.MetricQuery;

/**
 * 月目标达成率指标 provider(D4 北极星):包装 TargetService.achievement(签约线,
 * 节点=登录组织)——实绩口径与 target_plan 达成率同源,禁另写 SQL(docs/06 §2.3)。
 * rate 为千分比(1000=100%,target V1 存量口径),单位随声明 permille;
 * 当月未立目标 → no_data(正常空态,不冒充 0%)。
 */
@Component
public class TargetMetricProvider implements MetricProvider {

    public static final String KEY = "target.achievement.monthly";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TargetService targets;
    private final MessageSource messages;

    public TargetMetricProvider(TargetService targets, MessageSource messages) {
        this.targets = targets;
        this.messages = messages;
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
        Map<String, Object> result = targets.achievement(query.orgId(), query.orgId(), "contract", ym);
        Object rate = result.get("rate");
        if (rate == null) {
            return MetricData.noData(KEY, messages.getMessage("target.metric.noTarget", null,
                    LocaleContextHolder.getLocale()));
        }
        long value = ((Number) rate).longValue();
        int level = MetricData.levelOf(value, definition.thresholdWarn(), definition.thresholdCritical(),
                true);
        return MetricData.ok(KEY, java.time.LocalDateTime.now().toString(), definition.unit(), value,
                level);
    }
}
