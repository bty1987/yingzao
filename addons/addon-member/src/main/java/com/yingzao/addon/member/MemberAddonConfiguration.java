package com.yingzao.addon.member;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

/**
 * addon-member 装配入口:yingzao.addons.enabled 含 member 时生效。
 */
@AutoConfiguration
@ConditionalOnAddon("member")
@ComponentScan
public class MemberAddonConfiguration {
}
