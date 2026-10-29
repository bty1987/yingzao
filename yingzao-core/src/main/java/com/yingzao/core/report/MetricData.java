package com.yingzao.core.report;

import java.util.List;

/**
 * 指标数据(空态四分支内建,docs/06 §2.4):ok 正常 / pending 口径确认中(灰灯,宁可空不可错) /
 * no_data 正常无数据(绿态文案) / stale 上游延迟或 provider 异常(黄条,不以旧数冒充新数)。
 *
 * <p>asOf 统一时点:契约查询期直出=now,ads 聚合表=批次时点;level 告警级别由 provider
 * 按元数据阈值计算(0 正常 / 1 黄 / 2 红,阈值未配置恒 0)。
 */
public record MetricData(String metricKey, String asOf, String status, String unit,
        Long value, List<Point> series, String note, int level) {

    public record Point(String label, long value) {
    }

    public static final String STATUS_OK = "ok";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_NO_DATA = "no_data";
    public static final String STATUS_STALE = "stale";

    public static final int LEVEL_NORMAL = 0;
    public static final int LEVEL_WARN = 1;
    public static final int LEVEL_CRITICAL = 2;

    public static MetricData ok(String metricKey, String asOf, String unit, Long value, int level) {
        return new MetricData(metricKey, asOf, STATUS_OK, unit, value, List.of(), null, level);
    }

    public static MetricData series(String metricKey, String asOf, String unit, List<Point> series) {
        return new MetricData(metricKey, asOf, STATUS_OK, unit, null, series, null, LEVEL_NORMAL);
    }

    public static MetricData pending(String metricKey, String note) {
        return new MetricData(metricKey, null, STATUS_PENDING, null, null, List.of(), note, LEVEL_NORMAL);
    }

    public static MetricData noData(String metricKey, String note) {
        return new MetricData(metricKey, null, STATUS_NO_DATA, null, null, List.of(), note, LEVEL_NORMAL);
    }

    public static MetricData stale(String metricKey, String asOf, String note) {
        return new MetricData(metricKey, asOf, STATUS_STALE, null, null, List.of(), note, LEVEL_WARN);
    }

    /**
     * 告警级别判定(告警通用基线:阈值数值化,黄红两级;任一阈值为 0 = 该级未配置不告警)。
     *
     * @param lowerBetter true=越低越好(达成率/及时率),false=越高越好(违约/超期类)
     */
    public static int levelOf(long value, long warn, long critical, boolean lowerBetter) {
        if (critical != 0 && (lowerBetter ? value < critical : value > critical)) {
            return LEVEL_CRITICAL;
        }
        if (warn != 0 && (lowerBetter ? value < warn : value > warn)) {
            return LEVEL_WARN;
        }
        return LEVEL_NORMAL;
    }
}
