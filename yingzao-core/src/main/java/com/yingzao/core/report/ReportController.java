package com.yingzao.core.report;

import java.time.format.DateTimeFormatter;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

/**
 * 报表框架数据端点(F8 最小面;内核端点自带守卫,enforce 对内核 URI 豁免):
 * 目录端点下发指标五要素元数据,数据端点是聚合枢纽——看板只经这里取数,
 * 插件间零依赖(军规 2)。数据端点按登录人 orgScope 校验组织参数(124002)。
 */
@RestController
public class ReportController {

    public static final int CODE_SCOPE = 124002;
    public static final int CODE_BADREQUEST = 124003;

    private static final DateTimeFormatter AS_OF = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MetricRegistry registry;
    private final ReportEventService events;
    private final MessageSource messages;

    public ReportController(MetricRegistry registry, ReportEventService events,
            MessageSource messages) {
        this.registry = registry;
        this.events = events;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /** 指标目录:五要素元数据 + 口径确认状态 + provider 在位标记。 */
    @GetMapping("/api/report/metrics")
    public ApiResponse<List<MetricDefinition>> metrics() {
        return ApiResponse.ok(registry.definitions());
    }

    /**
     * 指标数据:参数=组织/月窗/区间窗(orgId 缺省=登录组织);返回数据 + asOf 统一时点 + 空态结构。
     * pending 指标返回灰灯空态(不抛错),越权 124002。
     */
    @GetMapping("/api/report/metrics/{key}/data")
    public ApiResponse<MetricData> data(@PathVariable String key,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String yearMonth,
            @RequestParam(required = false) String beginDay,
            @RequestParam(required = false) String endDay,
            HttpServletRequest request) {
        long callerOrg = (Long) request.getAttribute("yz.orgId");
        long targetOrg = orgId == null ? callerOrg : orgId;
        java.util.Collection<Long> scope = scopeOf(request);
        if (scope != null && !scope.contains(targetOrg)) {
            throw new BizException(CODE_SCOPE, msg("report.metric.scope"));
        }
        // 月窗格式+范围(2026-13 形态的格式合法月份非法要拒);区间窗成对,单日=仅 beginDay(工单日达成口径)
        if (yearMonth != null && !(yearMonth.matches("\\d{4}-(0[1-9]|1[0-2])"))) {
            throw new BizException(CODE_BADREQUEST, msg("report.metric.badrequest"));
        }
        if (beginDay != null && endDay != null && beginDay.compareTo(endDay) > 0) {
            throw new BizException(CODE_BADREQUEST, msg("report.metric.badrequest"));
        }
        return ApiResponse.ok(registry.fetch(key, new MetricQuery(targetOrg, yearMonth, beginDay, endDay)));
    }

    @SuppressWarnings("unchecked")
    private java.util.Collection<Long> scopeOf(HttpServletRequest request) {
        Object attr = request.getAttribute("yz.orgScope");
        return attr instanceof java.util.Collection ? (java.util.Collection<Long>) attr : null;
    }

    /** 看板埋点(5 类事件;服务编排不服务考核)。 */
    @PostMapping("/api/report/events")
    public ApiResponse<Void> track(@RequestBody ReportEventService.EventCmd cmd,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        events.record(orgId, userId, cmd, AS_OF.format(java.time.LocalDateTime.now()));
        return ApiResponse.ok(null);
    }
}
