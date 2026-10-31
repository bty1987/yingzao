package com.yingzao.core.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * TokenStore 双后端装配(F2 认证 Redis 化):缺省内存(spike 语义原样);
 * {@code yingzao.auth.store=redis} 切 Redis 后端。spring-boot-starter-data-redis 懒连接,
 * 内存模式全程零 redis 触达、零行为变化。
 */
@Configuration
public class TokenStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "yingzao.auth.store", havingValue = "memory", matchIfMissing = true)
    public TokenStore inMemoryTokenStore(@Value("${yingzao.auth.ttl-hours:12}") long ttlHours) {
        return new InMemoryTokenStore(ttlHours);
    }

    @Bean
    @ConditionalOnProperty(name = "yingzao.auth.store", havingValue = "redis")
    public TokenStore redisTokenStore(StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${yingzao.auth.ttl-hours:12}") long ttlHours) {
        return new RedisTokenStore(redis, mapper, ttlHours);
    }
}
