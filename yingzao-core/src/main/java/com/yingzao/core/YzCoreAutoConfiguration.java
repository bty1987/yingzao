package com.yingzao.core;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.yingzao.core.addon.AddonRegistry;

/**
 * 内核装配入口:组件扫描(统一响应/异常/认证/插件端点/事件总线)+ 插件注册表。
 * i18n 走 Boot 标准配置(spring.messages.basename=i18n/messages);
 * @EnableScheduling 供事件总线投递器等内核平台设施使用。
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan("com.yingzao.core")
public class YzCoreAutoConfiguration {

    @Bean
    public AddonRegistry yzAddonRegistry(@Value("${yingzao.addons.enabled:}") String enabled) {
        List<String> ids = Arrays.stream(enabled.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new AddonRegistry(ids);
    }
}
