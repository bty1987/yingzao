package com.yingzao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 营造组装壳:仅作启动入口,业务一律经内核 AutoConfiguration + ConditionalOnAddon 装配。
 */
@SpringBootApplication
public class YzApplication {

    public static void main(String[] args) {
        SpringApplication.run(YzApplication.class, args);
    }
}
