package com.yingzao.core.addon;

import java.util.Arrays;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 读取 yingzao.addons.enabled 判断 addon 是否启用。
 */
public class OnAddonCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attrs = metadata.getAnnotationAttributes(ConditionalOnAddon.class.getName());
        if (attrs == null) {
            return false;
        }
        String required = (String) attrs.get("value");
        String enabled = context.getEnvironment().getProperty("yingzao.addons.enabled", "");
        return Arrays.stream(enabled.split(","))
                .map(String::trim)
                .anyMatch(required::equals);
    }
}
