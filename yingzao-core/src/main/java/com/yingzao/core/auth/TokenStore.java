package com.yingzao.core.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * spike 用内存 token 存储;正式版换 Redis。
 */
@Component
public class TokenStore {

    public record Token(Long userId, Long orgId, Instant expiresAt) {
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public String issue(Long userId, Long orgId) {
        String token = java.util.UUID.randomUUID().toString();
        tokens.put(token, new Token(userId, orgId, Instant.now().plusSeconds(12 * 3600)));
        return token;
    }

    public Token verify(String token) {
        Token t = tokens.get(token);
        if (t == null || t.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return t;
    }
}
