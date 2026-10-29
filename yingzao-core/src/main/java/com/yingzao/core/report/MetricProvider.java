package com.yingzao.core.report;

/**
 * 指标提供方 SPI(报表框架 F8 最小面):内核定义,业务 addon 实现并注册为 Spring bean,
 * {@link MetricRegistry} 启动时按 metricKey 与 addon.yml metrics 段声明对位组装。
 * 内核数据端点=聚合枢纽,看板等消费方只经 Registry 取数,插件间零依赖(军规 2)。
 */
public interface MetricProvider extends com.yingzao.core.addon.YzExtensionPoint {

    /** 指标 key(全局唯一,与 addon.yml metrics 段声明对位;无声明的 provider 被忽略)。 */
    String metricKey();

    /**
     * 取数。definition 为本指标的五要素元数据(告警阈值数值化在其中,provider 据此经
     * {@link MetricData#levelOf} 自算 level)。返回空态结构(pending/no_data/stale)不算失败;
     * 抛异常由 Registry 降级为 stale 黄条(该卡降级不炸整页)。
     */
    MetricData fetch(MetricQuery query, MetricDefinition definition);
}
