package com.yingzao.addon.basedata;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

@AutoConfiguration
@ConditionalOnAddon("basedata")
@ComponentScan
public class BasedataAddonConfiguration {
}
