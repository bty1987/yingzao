package com.yingzao.core.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import com.yingzao.core.addon.AddonDescriptor;
import com.yingzao.core.addon.AddonRegistry;
import com.yingzao.core.web.BizException;

/**
 * 指标注册表(报表框架 F8 最小面的聚合枢纽):启动时把 addon.yml metrics 段声明与
 * MetricProvider bean 按 key 对位组装;消费方(看板等)一律经 {@link #fetch} 取数,
 * 插件间零依赖(军规 2)。指标 key 全局唯一,注册期查重——重复声明告警并保留首个。
 *
 * <p>取数分发:未声明 → 124001;口径未确认(pending) → 空态灰灯结构,宁可空不可错;
 * 已确认无 provider → 124005(声明与实现失配是部署错误,不该静默);provider 异常 →
 * 降级 stale 黄条(该卡降级不炸整页)。
 */
public class MetricRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricRegistry.class);

    /** 内核错误码段 124000(报表框架;124xxx 空闲段,2026-10-30 实证)。 */
    public static final int CODE_NOTFOUND = 124001;
    public static final int CODE_NO_PROVIDER = 124005;

    private final MessageSource messages;
    private final Map<String, Definition> defs = new HashMap<>();

    /** 一条指标的声明(已解析展示文本)与其 provider(可缺失)。 */
    public record Definition(MetricDefinition meta, MetricProvider provider) {
    }

    public MetricRegistry(AddonRegistry addons, List<MetricProvider> providers,
            MessageSource messages) {
        this.messages = messages;
        Map<String, MetricProvider> byKey = new HashMap<>();
        for (MetricProvider p : providers) {
            byKey.put(p.metricKey(), p);
        }
        for (AddonDescriptor d : addons.enabled()) {
            for (AddonDescriptor.Metric m : d.metrics()) {
                if (defs.containsKey(m.key())) {
                    log.warn("metric key {} redeclared by addon {}, keep first", m.key(), d.id());
                    continue;
                }
                MetricProvider provider = byKey.get(m.key());
                if (provider != null) {
                    byKey.remove(m.key());
                }
                if (MetricDefinition.STATUS_CONFIRMED.equals(m.status()) && provider == null) {
                    log.warn("metric {} declared confirmed but has no provider (addon {})",
                            m.key(), d.id());
                }
                defs.put(m.key(), new Definition(toMeta(m, d.id(), provider != null), provider));
            }
        }
        for (String orphan : byKey.keySet()) {
            log.warn("metric provider {} has no addon.yml declaration, ignored", orphan);
        }
        log.info("metric registry: {} metrics registered ({} pending)",
                defs.size(), defs.values().stream()
                        .filter(x -> MetricDefinition.STATUS_PENDING.equals(x.meta().status())).count());
    }

    private MetricDefinition toMeta(AddonDescriptor.Metric m, String addonId, boolean hasProvider) {
        return new MetricDefinition(m.key(), msg(m.nameKey()), msg(m.descKey()), msg(m.sourceKey()),
                m.frequency(), m.compare(), m.unit(), m.thresholdWarn(), m.thresholdCritical(),
                m.status(), addonId, hasProvider);
    }

    /** 展示文本解析(取用姿势同内核惯例:LocaleContextHolder)。 */
    private String msg(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return messages.getMessage(key, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }

    /** 指标目录(按 key 排序,前端渲染与冒烟断言稳定)。 */
    public List<MetricDefinition> definitions() {
        List<MetricDefinition> out = new ArrayList<>();
        for (Definition d : defs.values()) {
            out.add(withProviderFlag(d));
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    public MetricDefinition definition(String key) {
        Definition d = defs.get(key);
        if (d == null) {
            throw new BizException(CODE_NOTFOUND, msg("report.metric.notfound"));
        }
        return withProviderFlag(d);
    }

    /**
     * 取数:pending → 灰灯空态;confirmed 无 provider → 124005;provider 异常 → stale 降级。
     */
    public MetricData fetch(String key, MetricQuery query) {
        Definition d = defs.get(key);
        if (d == null) {
            throw new BizException(CODE_NOTFOUND, msg("report.metric.notfound"));
        }
        if (MetricDefinition.STATUS_PENDING.equals(d.meta().status())) {
            return MetricData.pending(key, msg("report.metric.pending"));
        }
        if (d.provider() == null) {
            throw new BizException(CODE_NO_PROVIDER, msg("report.metric.noprovider"));
        }
        try {
            return d.provider().fetch(query, d.meta());
        } catch (Exception e) {
            log.warn("metric {} provider degraded: {}", key, e.getMessage());
            return MetricData.stale(key, null, msg("report.metric.degraded"));
        }
    }

    private MetricDefinition withProviderFlag(Definition d) {
        MetricDefinition m = d.meta();
        return new MetricDefinition(m.key(), m.name(), m.description(), m.source(), m.frequency(),
                m.compare(), m.unit(), m.thresholdWarn(), m.thresholdCritical(), m.status(),
                m.addonId(), d.provider() != null);
    }
}
