package com.yingzao.core.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 计划任务调度器:提交后同步执行一轮之后的兜底——失败退避到期的、指定未来时刻执行的、
 * 应用重启遗留的、执行器崩溃超时回收的,都在这里被再次捡起。默认 30 秒一轮,单轮上限 10 条。
 */
@Component
public class PlanDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PlanDispatcher.class);

    private final PlanService plans;

    public PlanDispatcher(PlanService plans) {
        this.plans = plans;
    }

    @Scheduled(fixedDelayString = "${yingzao.plan.poll-interval-millis:30000}",
            initialDelayString = "${yingzao.plan.poll-initial-delay-millis:10000}")
    public void poll() {
        try {
            plans.dispatchDue(10);
        } catch (Exception e) {
            log.warn("plan dispatch round failed: {}", e.getMessage());
        }
    }
}
