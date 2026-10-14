package com.yingzao.addon.member;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 积分过期清算调度器(V1.1):每日 03:00(可配 cron)扫开启 expire_clear_enabled 的组织,
 * 逐组织执行 expireClear(过期正向批次生成清算负行,uk 幂等锚重放安全)。
 * 默认开关关 = 行为不变(过期只拦兑换不清零,V1 语义);调度失败不抛出(记录后次轮重试,
 * 清算本身幂等)。
 */
@Component
public class MemberExpireClearScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemberExpireClearScheduler.class);

    private final JdbcTemplate jdbc;
    private final MemberPointService points;
    private final boolean enabled;

    public MemberExpireClearScheduler(JdbcTemplate jdbc, MemberPointService points,
            @Value("${yingzao.member.clear-schedule-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.points = points;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${yingzao.member.clear-cron:0 0 3 * * ?}")
    public void run() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> orgs = jdbc.queryForList(
                "select org_id from member_config where expire_clear_enabled = 1");
        for (Map<String, Object> org : orgs) {
            long orgId = ((Number) org.get("org_id")).longValue();
            try {
                Map<String, Object> r = points.expireClear(orgId);
                log.info("member expire-clear org={} cleared={} customers={}",
                        orgId, r.get("cleared"), r.get("customers"));
            } catch (Exception e) {
                // 单组织失败不影响其他组织;清算幂等,次轮自动补
                log.error("member expire-clear failed org={}", orgId, e);
            }
        }
    }
}
