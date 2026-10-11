package com.yingzao.addon.inventory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

@AutoConfiguration
@ConditionalOnAddon("inventory")
@ComponentScan
public class InventoryAddonConfiguration {
}
