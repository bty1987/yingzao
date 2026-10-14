package com.yingzao.addon.portal;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

/**
 * addon-portal 装配入口:yingzao.addons.enabled 含 portal 时生效。
 */
@AutoConfiguration
@ConditionalOnAddon("portal")
@ComponentScan
public class PortalAddonConfiguration {
}
