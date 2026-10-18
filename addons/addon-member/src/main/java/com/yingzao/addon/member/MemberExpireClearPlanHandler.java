package com.yingzao.addon.member;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
 * 积分过期清算计划处理器(V1.2 调度迁移):yz_plan 内核的首个业务消费方——
 * 取代 MemberExpireClearScheduler 的 @Scheduled 硬调度,清算执行/下一次排程/运行历史
 * 全部收敛进 yz_plan(可在 /api/plans 管理页与 Events 同款查看/取消/死信重试)。
 *
 * <p>三重角色:①YzPlanHandler(handler="member-expire-clear",一次执行=清算一个组织的
 * 过期批次);②配置态收敛(组织开关 expire_clear_enabled 开=确保下一轮 03:00 计划在挂,
 * 关=撤销在挂计划,均幂等);③启动对账(扫已开启组织补挂缺失计划,部署自举+断链自愈)。
 *
 * <p>续期模型:yz_plan 为一次性任务,每日循环靠「链式续期」——每次执行在同事务内先挂
 * 下一轮 03:00 的计划(biz_key=member-expire-clear-{org}-{yyyyMMdd} 按日幂等,重试重放
 * 撞在挂计划即跳过),再做本日清算;计划死亡(退避重试超限)则链条停摆,死信在管理页
 * 可见可重试,下次重启对账也会自愈补挂。
 */
@Component
public class MemberExpireClearPlanHandler implements YzPlanHandler {

    private static final Logger log = LoggerFactory.getLogger(MemberExpireClearPlanHandler.class);

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final MemberPointService points;
    private final PlanService plans;

    public MemberExpireClearPlanHandler(JdbcTemplate jdbc, MemberPointService points,
            @Lazy PlanService plans) {
        this.jdbc = jdbc;
        this.points = points;
        this.plans = plans;
    }

    @Override
    public String handler() {
        return "member-expire-clear";
    }

    /** 单组织清算 + 链式续期(同事务:续期插入随清算成败一起提交/回滚)。 */
    @Override
    @Transactional
    public PlanResult execute(long orgId, long planId, JsonNode params) {
        String chained = ensureNextDaily(orgId);
        Map<String, Object> r = points.expireClear(orgId);
        return PlanResult.of("cleared=" + r.get("cleared") + " customers=" + r.get("customers")
                + " candidates=" + r.get("candidates") + " next=" + chained);
    }

    // ---- 配置态收敛(控制器开关路径 + 启动对账共用) ----

    /**
     * 确保下一轮 03:00 的清算计划在挂(幂等:同 biz_key 已有在挂/重试中计划即跳过)。
     * 调用方须保证该组织已开启 expire_clear_enabled。
     */
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

    /** 撤销该组织在挂的清算计划(0 待执行/2 重试中 → 4 已取消;幂等)。 */
    public int cancelScheduled(long orgId) {
        return jdbc.update(
                "update yz_plan set status = 4 where org_id = ? and handler = ? and status in (0, 2)",
                orgId, handler());
    }

    /** 启动对账:扫已开启组织补挂缺失计划(部署自举;断链自愈=每次重启兜底)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        List<Long> orgs = jdbc.queryForList(
                "select org_id from member_config where expire_clear_enabled = 1", Long.class);
        for (Long orgId : orgs) {
            try {
                long id = ensureScheduled(orgId);
                log.info("member expire-clear reconcile org={} plan={}", orgId, id);
            } catch (Exception e) {
                log.error("member expire-clear reconcile failed org={}", orgId, e);
            }
        }
    }

    // ---- 内部 ----

    /** 链式续期:挂下一轮 03:00 计划;开关已关(执行间隙被关)则不续,返回摘要。 */
    private String ensureNextDaily(long orgId) {
        Integer enabled = jdbc.queryForObject(
                "select expire_clear_enabled from member_config where org_id = ?",
                Integer.class, orgId);
        if (enabled == null || enabled != 1) {
            return "off";
        }
        LocalDateTime next = nextThreeAm();
        String bizKey = bizKey(orgId, next);
        Integer exists = jdbc.queryForObject("""
                select count(*) from yz_plan
                where org_id = ? and handler = ? and biz_key = ? and status in (0, 2)
                """, Integer.class, orgId, handler(), bizKey);
        if (exists != null && exists > 0) {
            return next.format(DAY_KEY) + "(existing)";
        }
        plans.submit(orgId, handler(), bizKey, null, 3, next, null);
        return next.format(DAY_KEY);
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
