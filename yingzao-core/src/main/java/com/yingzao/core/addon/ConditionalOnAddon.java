package com.yingzao.core.addon;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * 标记 AutoConfiguration/Bean 仅在指定 addon 启用时装配。
 * 启用清单来自配置 yingzao.addons.enabled(逗号分隔)。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnAddonCondition.class)
public @interface ConditionalOnAddon {

    String value();
}
