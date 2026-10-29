package com.yingzao.core.report;

/**
 * 指标五要素元数据(docs/06 §2.1,已按 addon.yml 声明解析为展示文本):
 * 口径定义(description,分子/分母/时间窗写到不追问)/数据来源(source,精确到表或契约)/
 * 更新频率(frequency)/对比(compare)/告警阈值(黄红两级数值化)。
 * 声明与 provider 实现分离:声明在 addon.yml metrics 段,provider 缺失时 hasProvider=false。
 */
public record MetricDefinition(String key, String name, String description, String source,
        String frequency, String compare, String unit,
        long thresholdWarn, long thresholdCritical,
        String status, String addonId, boolean hasProvider) {

    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_PENDING = "pending";
}
