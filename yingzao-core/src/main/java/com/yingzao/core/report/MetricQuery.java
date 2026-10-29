package com.yingzao.core.report;

/**
 * 指标查询参数(统一时点纪律:混合时点每卡显式,禁全页假装同时点)。
 *
 * <p>两类时间窗二选一:月指标带 yearMonth(yyyy-MM),区间指标带 beginDay/endDay(yyyy-MM-dd);
 * provider 按自身口径取用,Registry 不做二次解释。
 *
 * @param orgId     组织(数据端点已按登录人 orgScope 校验,provider 内不必复查)
 * @param yearMonth 月窗(yyyy-MM;月指标用)
 * @param beginDay  起日(yyyy-MM-dd;区间/单日指标用)
 * @param endDay    止日(yyyy-MM-dd,含端)
 */
public record MetricQuery(long orgId, String yearMonth, String beginDay, String endDay) {

    public static MetricQuery ofMonth(long orgId, String yearMonth) {
        return new MetricQuery(orgId, yearMonth, null, null);
    }

    public static MetricQuery ofRange(long orgId, String beginDay, String endDay) {
        return new MetricQuery(orgId, null, beginDay, endDay);
    }
}
