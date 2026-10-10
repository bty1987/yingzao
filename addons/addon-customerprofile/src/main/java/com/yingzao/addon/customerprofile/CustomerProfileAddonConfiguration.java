package com.yingzao.addon.customerprofile;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

import com.yingzao.core.addon.ConditionalOnAddon;

@AutoConfiguration
@ConditionalOnAddon("customerprofile")
@ComponentScan
public class CustomerProfileAddonConfiguration {
}
