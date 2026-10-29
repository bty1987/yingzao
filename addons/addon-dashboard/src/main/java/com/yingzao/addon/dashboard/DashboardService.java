package com.yingzao.addon.dashboard;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import com.yingzao.core.report.MetricData;
import com.yingzao.core.report.MetricDefinition;
import com.yingzao.core.report.MetricQuery;
import com.yingzao.core.report.MetricRegistry;

/**
 * 驾驶舱聚合首页服务:六维健康卡 + 结论条(异常置顶,最多 3 句)+ 签约-回款双线
 * (近 6 个月)+ 大区排名——全部数据经内核 MetricRegistry 取数,与业务插件零耦合
 * (军规 2:插件只依赖内核扩展点)。provider 异常已在 Registry 层降级 stale,逐卡再兜底。
 *
 * <p>六维卡绑定 V1 为常量(卡位=布局不是指标,指标口径已在 metrics 声明);每卡带
 * 指标定义(name/五要素)与数据(status/level),pending 卡灰灯由数据端自然呈现。
 */
@Service
public class DashboardService {

    /** 六维卡位(docs/06 §4 信息架构;客户维=漏斗族,R33 前置灰灯)。 */
    private static final String[] CARD_KEYS = {
            "decoration.contract.signedMonthly",   // 业绩营销
            "target.achievement.monthly",          // 经营管理
            "construction.workload.dailyRate",     // 工程交付
            "sales.performance.materialMonthly",   // 供应链
            "payment.collection.operatingMonthly", // 财务
            "customer.funnel.stages",              // 客户(漏斗族,R33 前置 pending)
    };

    private static final String SIGN_KEY = "decoration.contract.signedMonthly";
    private static final String COLLECTION_KEY = "payment.collection.operatingMonthly";
    /** 日粒度指标卡(工单当日达成率):单日查询(beginDay=昨日,T+1),其余卡按月窗。 */
    private static final String DAILY_CARD_KEY = "construction.workload.dailyRate";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MetricRegistry registry;
    private final MessageSource messages;

    public DashboardService(MetricRegistry registry, MessageSource messages) {
        this.registry = registry;
        this.messages = messages;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** 首页聚合体:结论条 + 六维卡(定义+数据)+ 双线 + 大区排名。 */
    public Map<String, Object> home(long orgId) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String key : CARD_KEYS) {
            Map<String, Object> card = new LinkedHashMap<>();
            MetricDefinition def = safeDefinition(key);
            if (def != null) {
                card.put("metric", def);
            }
            card.put("key", key);
            MetricQuery q = DAILY_CARD_KEY.equals(key)
                    ? MetricQuery.ofRange(orgId, DAY.format(LocalDate.now().minusDays(1)), null)
                    : MetricQuery.ofMonth(orgId, MONTH.format(LocalDate.now()));
            MetricData data = safeFetch(key, q);
            card.put("data", data);
            cards.add(card);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("conclusion", conclusion(cards));
        out.put("dualLines", dualLines(orgId));
        out.put("regionRank", safeFetch(RegionRankMetricProvider.KEY,
                MetricQuery.ofMonth(orgId, MONTH.format(LocalDate.now()))));
        return out;
    }

    /** 结论条:异常卡置顶(红>黄>数据延迟),最多 3 句;全绿一句平稳。 */
    private List<Map<String, Object>> conclusion(List<Map<String, Object>> cards) {
        record Row(int rank, int level, String text) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> card : cards) {
            MetricData data = (MetricData) card.get("data");
            String name = cardName(card);
            if (data == null) {
                continue;
            }
            if (MetricData.STATUS_STALE.equals(data.status())) {
                rows.add(new Row(0, MetricData.LEVEL_WARN,
                        msg("dashboard.conclusion.stale", name)));
            } else if (data.level() >= MetricData.LEVEL_CRITICAL) {
                rows.add(new Row(0, MetricData.LEVEL_CRITICAL,
                        msg("dashboard.conclusion.critical", name)));
            } else if (data.level() >= MetricData.LEVEL_WARN) {
                rows.add(new Row(0, MetricData.LEVEL_WARN,
                        msg("dashboard.conclusion.warn", name)));
            }
        }
        rows.sort(Comparator.comparingInt(Row::level).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows.isEmpty()) {
            out.add(Map.of("level", MetricData.LEVEL_NORMAL, "text",
                    msg("dashboard.conclusion.allgreen")));
            return out;
        }
        for (Row r : rows.stream().limit(3).toList()) {
            out.add(Map.of("level", r.level(), "text", r.text()));
        }
        return out;
    }

    private String cardName(Map<String, Object> card) {
        Object metric = card.get("metric");
        if (metric instanceof MetricDefinition def && def.name() != null) {
            return def.name();
        }
        return String.valueOf(card.get("key"));
    }

    /** 签约-回款双线(近 6 个月含当月;单月取数失败按 0 断线,不炸整页)。 */
    private Map<String, Object> dualLines(long orgId) {
        List<String> months = new ArrayList<>();
        List<MetricData.Point> sign = new ArrayList<>();
        List<MetricData.Point> collection = new ArrayList<>();
        LocalDate cur = LocalDate.now().withDayOfMonth(1).minusMonths(5);
        for (int i = 0; i < 6; i++) {
            String ym = MONTH.format(cur);
            months.add(ym);
            sign.add(pointOf(SIGN_KEY, orgId, ym));
            collection.add(pointOf(COLLECTION_KEY, orgId, ym));
            cur = cur.plusMonths(1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", months);
        out.put("sign", sign);
        out.put("collection", collection);
        return out;
    }

    private MetricData.Point pointOf(String key, long orgId, String ym) {
        MetricData data = safeFetch(key, MetricQuery.ofMonth(orgId, ym));
        if (data == null || data.value() == null) {
            return new MetricData.Point(ym, 0);
        }
        return new MetricData.Point(ym, data.value());
    }

    private MetricDefinition safeDefinition(String key) {
        try {
            return registry.definition(key);
        } catch (Exception e) {
            return null;
        }
    }

    private MetricData safeFetch(String key, MetricQuery query) {
        try {
            return registry.fetch(key, query);
        } catch (Exception e) {
            return MetricData.stale(key, null, msg("dashboard.card.degraded"));
        }
    }
}
