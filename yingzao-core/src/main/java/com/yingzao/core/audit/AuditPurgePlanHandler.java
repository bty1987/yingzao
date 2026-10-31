package com.yingzao.core.audit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * 审计日志保留策略(F3,docs/15 R24~R25):yz_plan 链式续期(member-expire-clear 先例)——
 * handler=audit-log-purge,每日 03:00 按 cutoff 批量 DELETE 超期行;启动对账补挂(部署自举+
 * 断链自愈)。retention-days=0 时执行空转但链条不断(再开启无需重启);params.retentionDays
 * 可单次覆盖全局配置(冒烟/应急)。
 */
@Component
public class AuditPurgePlanHandler implements YzPlanHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditPurgePlanHandler.class);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final long retentionDays;

    public AuditPurgePlanHandler(JdbcTemplate jdbc, @Lazy PlanService plans,
            @Value("${yingzao.audit.retention-days:90}") long retentionDays) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.retentionDays = retentionDays;
    }

    @Override
    public String handler() {
        return "audit-log-purge";
    }

    /** 批量 DELETE 超期行 + 链式续期(同事务:续期随清理成败一起提交/回滚)。 */
    @Override
    @Transactional
    public PlanResult execute(long orgId, long planId, JsonNode params) {
        long chained = ensureNextDaily();
        long days = retentionDays;
        if (params != null && params.hasNonNull("retentionDays")) {
            days = params.get("retentionDays").asLong();
        }
        long purged;
        if (days <= 0) {
            purged = 0;
            log.info("[audit-purge] retention disabled (days=0), skip delete");
        } else {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
            purged = 0;
            while (true) {
                int n = jdbc.update(
                        "delete from yz_audit_log where created_at < ? limit ?", cutoff, BATCH);
                purged += n;
                if (n < BATCH) {
                    break;
                }
            }
        }
        return PlanResult.of("purged=" + purged + " retention=" + days + " next=" + chained);
    }

    // ---- 链式续期与启动对账(member-expire-clear 同构,全局单链 org=1) ----

    /** 确保下一轮 03:00 的清理计划在挂(幂等:同 biz_key 已有在挂/重试中计划即跳过)。 */
    public long ensureScheduled() {
        LocalDateTime next = nextThreeAm();
        String bizKey = handler() + "-" + next.format(DAY_KEY);
        Integer exists = jdbc.queryForObject("""
                select count(*) from yz_plan
                where handler = ? and biz_key = ? and status in (0, 2)
                """, Integer.class, handler(), bizKey);
        if (exists != null && exists > 0) {
            return 0;
        }
        return plans.submit(1, handler(), bizKey, null, 3, next, null);
    }

    /** 启动对账:补挂缺失的当日链条(部署自举;断链由下次重启自愈)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            ensureScheduled();
        } catch (Exception e) {
            log.error("[audit-purge] startup reconcile failed", e);
        }
    }

    private long ensureNextDaily() {
        return ensureScheduled();
    }

    private LocalDateTime nextThreeAm() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(3).withMinute(0).withSecond(0).withNano(0);
        return next.isAfter(now) ? next : next.plusDays(1);
    }
}
