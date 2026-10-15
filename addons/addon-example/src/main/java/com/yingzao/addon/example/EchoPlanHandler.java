package com.yingzao.addon.example;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingzao.core.plan.YzPlanHandler;

/**
 * 示范计划处理器(handler="echo"):计划任务设施端到端的参考实现——
 * 成功路径回显 params.message;params.fail 真值时抛异常(供冒烟走退避重试→死信→retry 链)。
 * 业务插件的真实处理器姿势同款:实现 YzPlanHandler + 注册名前缀插件 id。
 */
@Component
public class EchoPlanHandler implements YzPlanHandler {

    @Override
    public String handler() {
        return "echo";
    }

    @Override
    public PlanResult execute(long orgId, long planId, JsonNode params) {
        if (params != null && params.has("fail") && params.get("fail").asBoolean(false)) {
            throw new IllegalStateException("echo forced fail (planId=" + planId + ")");
        }
        String message = params != null && params.has("message")
                ? params.get("message").asText("") : "";
        return PlanResult.of("echo:" + message);
    }
}
