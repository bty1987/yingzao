package com.yingzao.addon.target;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

/**
 * addon-target 装配入口:yingzao.addons.enabled 含 target 时生效。
 */
@AutoConfiguration
@ConditionalOnAddon("target")
@ComponentScan
public class TargetAddonConfiguration {
}
