package com.yingzao.core;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import com.yingzao.core.addon.AddonRegistry;

/**
 * 内核装配入口:组件扫描(统一响应/异常/认证/插件端点)+ 插件注册表。
 * i18n 走 Boot 标准配置(spring.messages.basename=i18n/messages)。
 */
@AutoConfiguration
@ComponentScan("com.yingzao.core")
public class YzCoreAutoConfiguration {

    @Bean
    public AddonRegistry yzAddonRegistry(@Value("${yingzao.addons.enabled:}") String enabled) {
        List<String> ids = Arrays.stream(enabled.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new AddonRegistry(ids);
    }
}
