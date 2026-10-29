package com.yingzao.addon.construction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingzao.core.plan.PlanService;
import com.yingzao.core.plan.YzPlanHandler;

/**
 * 工单日达成聚合日刷 handler(D4 北极星,member-expire-clear/PK 归档同构:yz_plan 链式)——
 * 每次执行 REPLACE INTO 近 90 天滚动全量重刷(ads-reference 模式 2,快照重算天然幂等),
 * batch_time=批次时点(provider asOf 来源)。
 *
 * <p>三重角色:①YzPlanHandler(handler="construction-ads-wo-task-daily",一次执行=刷一个组织);
 * ②链式续期(执行同事务挂下一轮 03:00,biz_key 按日幂等——ads 日刷永续,不断链);
 * ③启动对账(扫有施工单的组织补挂缺失计划,部署自举+断链自愈)。
 */
@Component
public class WorkloadAdsPlanHandler implements YzPlanHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkloadAdsPlanHandler.class);

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final int ROLL_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final PlanService plans;

    public WorkloadAdsPlanHandler(JdbcTemplate jdbc, @Lazy PlanService plans) {
        this.jdbc = jdbc;
        this.plans = plans;
    }

    @Override
    public String handler() {
        return "construction-ads-wo-task-daily";
    }

    /** 近 90 天滚动重刷 + 链式续期(同事务)。 */
    @Override
    @Transactional
    public PlanResult execute(long orgId, long planId, JsonNode params) {
        LocalDate today = LocalDate.now();
        int days = refresh(orgId, today.minusDays(ROLL_DAYS - 1), today.minusDays(1));
        long chained = ensureScheduled(orgId);
        return PlanResult.of("refreshed=" + days + "d next=" + (chained > 0 ? "chained" : "existing"));
    }

    /** 重刷 [begin, end] 窗口的聚合行,返回窗口天数(供 result 摘要)。 */
    public int refresh(long orgId, LocalDate begin, LocalDate end) {
        jdbc.update("""
                replace into construction_ads_wo_task_daily
                  (stat_date, org_id, due_cnt, done_cnt, done_rate, batch_time)
                select date(s.plan_end), s.org_id, count(*),
                       sum(case when s.end_time is not null and s.end_time <= s.plan_end
                                then 1 else 0 end),
                       case when count(*) = 0 then 0
                            else round(sum(case when s.end_time is not null
                                       and s.end_time <= s.plan_end then 1 else 0 end)
                                       * 10000 / count(*)) end,
                       now()
                from construction_schedule s
                where s.org_id = ? and s.plan_end is not null
                  and date(s.plan_end) >= ? and date(s.plan_end) <= ?
                group by date(s.plan_end), s.org_id
                """, orgId, java.sql.Date.valueOf(begin), java.sql.Date.valueOf(end));
        return (int) java.time.temporal.ChronoUnit.DAYS.between(begin, end) + 1;
    }

    /** 确保下一轮 03:00 的日刷计划在挂(幂等:同 biz_key 已有在挂/重试中计划即跳过)。 */
    public long ensureScheduled(long orgId) {
        LocalDateTime next = nextThreeAm();
        String bizKey = handler() + "-" + orgId + "-" + next.format(DAY_KEY);
        Integer exists = jdbc.queryForObject("""
                select count(*) from yz_plan
                where org_id = ? and handler = ? and biz_key = ? and status in (0, 2)
                """, Integer.class, orgId, handler(), bizKey);
        if (exists != null && exists > 0) {
            return 0;
        }
        return plans.submit(orgId, handler(), bizKey, null, 3, next, null);
    }

    /** 启动对账:扫有施工单的组织补挂缺失计划(部署自举;断链自愈=每次重启兜底)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        List<Long> orgs = jdbc.queryForList(
                "select distinct org_id from construction_order", Long.class);
        for (Long orgId : orgs) {
            try {
                long id = ensureScheduled(orgId);
                log.info("construction ads reconcile org={} plan={}", orgId, id);
            } catch (Exception e) {
                log.error("construction ads reconcile failed org={}", orgId, e);
            }
        }
    }

    /** 下一个严格晚于当前时刻的 03:00(链式计划永远指向未来,claim 到期谓词才放行)。 */
    private LocalDateTime nextThreeAm() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(3, 0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next;
    }
}
