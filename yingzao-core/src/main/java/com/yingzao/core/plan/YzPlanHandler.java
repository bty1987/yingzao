package com.yingzao.core.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 异步任务处理器 SPI(内核平台设施):插件实现本接口并注册为 Spring bean,
 * {@link PlanService} 按注册名匹配调度执行。与 {@link com.yingzao.core.eventbus.YzEventConsumer}
 * 同姿势的区别:事件=广播(一事件多消费者,失败重试整条),计划=定点(一任务一处理器,
 * 失败退避重试直至死信,业务语义是「待办」而非「通知」)。
 *
 * <p>契约:①execute 抛任何异常 = 本次执行失败,按退避重试,超限转死信(管理端可见可重试);
 * ②重试会重放 execute,处理器必须幂等(或以 biz_key 自查去重);③result 是给人看的摘要,
 * 成功/失败都建议带业务上下文;④execute 在调度线程执行,禁止依赖请求上下文(登录态/i18n locale)。
 */
public interface YzPlanHandler {

    /** 处理器注册名(提交任务时引用;全局唯一,建议前缀插件 id)。 */
    String handler();

    /**
     * 执行任务。正常返回 = 成功(result 落任务行);抛异常 = 失败(消息落 last_error,退避重试)。
     *
     * @param orgId   提交任务的组织
     * @param planId  任务行 id(重试重放时同 id,可作幂等锚)
     * @param params  提交时的参数 JSON(可空)
     */
    PlanResult execute(long orgId, long planId, JsonNode params);

    /** 执行结果:success 恒 true(失败走异常);result 为结果摘要。 */
    record PlanResult(String result) {
        public static PlanResult of(String result) {
            return new PlanResult(result);
        }
    }
}
