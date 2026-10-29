package com.yingzao.addon.target;

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
 * PK 赛事归档计划处理器(D3;member-expire-clear 同构:yz_plan 首个 target 域消费方)——
 * 赛事进行中(status=2)到期(end_date<今天)由定时任务冻结快照并归档,替代 fl-yy XXL-JOB
 * 「次月 2 日+手工 archive 恒拒」语义(归档只走快照链,快照时点=归档时点,零宽限歧义)。
 *
 * <p>三重角色:①YzPlanHandler(handler="target-pk-archive",一次执行=归档一个组织的到期赛事);
 * ②确认开赛时挂链(PkService.confirm 由控制器侧调 ensureScheduled);③启动对账(扫进行中赛事
 * 补挂缺失计划,部署自举+断链自愈)。
 *
 * <p>链式续期:每次执行后若该组织仍有进行中赛事则挂下一轮 03:00 计划
 * (biz_key=target-pk-archive-{org}-{yyyyMMdd} 按日幂等);全部归档则链条自然终止,
 * 下次 confirm 重新起链;计划死亡死信可见可重试。
 */
@Component
public class PkArchivePlanHandler implements YzPlanHandler {

    private static final Logger log = LoggerFactory.getLogger(PkArchivePlanHandler.class);

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final PkService pks;
    private final PlanService plans;

    public PkArchivePlanHandler(JdbcTemplate jdbc, PkService pks, @Lazy PlanService plans) {
        this.jdbc = jdbc;
        this.pks = pks;
        this.plans = plans;
    }

    @Override
    public String handler() {
        return "target-pk-archive";
    }

    /** 归档该组织到期赛事 + 链式续期(同事务:续期插入随归档成败一起提交/回滚)。 */
    @Override
    @Transactional
    public PlanResult execute(long orgId, long planId, JsonNode params) {
        int archived = pks.archiveDue(orgId);
        String chained = ensureNextDaily(orgId);
        return PlanResult.of("archived=" + archived + " events next=" + chained);
    }

    // ---- 确认开赛挂链 + 启动对账(控制器与启动共用) ----

    /** 确保下一轮 03:00 的归档计划在挂(幂等:同 biz_key 已有在挂/重试中计划即跳过)。 */
    public long ensureScheduled(long orgId) {
        LocalDateTime next = nextThreeAm();
        String bizKey = bizKey(orgId, next);
        Integer exists = jdbc.queryForObject("""
                select count(*) from yz_plan
                where org_id = ? and handler = ? and biz_key = ? and status in (0, 2)
                """, Integer.class, orgId, handler(), bizKey);
        if (exists != null && exists > 0) {
            return 0;
        }
        return plans.submit(orgId, handler(), bizKey, null, 3, next, null);
    }

    /** 链式续期:该组织仍有进行中赛事才挂下一轮 03:00(全部归档则链条自然终止)。 */
    private String ensureNextDaily(long orgId) {
        Integer running = jdbc.queryForObject(
                "select count(*) from target_pk_event where org_id = ? and status = 2",
                Integer.class, orgId);
        if (running == null || running == 0) {
            return "off";
        }
        long id = ensureScheduled(orgId);
        return id > 0 ? ("chained(" + id + ")") : "existing";
    }

    /** 启动对账:扫进行中赛事的组织补挂缺失计划(部署自举;断链自愈=每次重启兜底)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        List<Long> orgs = jdbc.queryForList(
                "select distinct org_id from target_pk_event where status = 2", Long.class);
        for (Long orgId : orgs) {
            try {
                long id = ensureScheduled(orgId);
                log.info("target pk archive reconcile org={} plan={}", orgId, id);
            } catch (Exception e) {
                log.error("target pk archive reconcile failed org={}", orgId, e);
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

    private String bizKey(long orgId, LocalDateTime runAt) {
        return handler() + "-" + orgId + "-" + runAt.format(DAY_KEY);
    }
}
