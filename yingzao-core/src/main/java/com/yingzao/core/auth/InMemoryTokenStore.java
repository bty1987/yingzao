package com.yingzao.core.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 TokenStore(spike 缺省后端):重启丢全会话、不可水平扩展;
 * 生产/多实例部署切 {@code yingzao.auth.store=redis}({@link RedisTokenStore})。
 */
class InMemoryTokenStore implements TokenStore {

    /** 缺省 12h;yingzao.auth.ttl-hours 可调(与 Redis 后端同语义)。 */
    private final long ttlSeconds;

    InMemoryTokenStore(long ttlHours) {
        this.ttlSeconds = ttlHours * 3600;
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    @Override
    public String issue(Long userId, Long orgId, String principal, Long customerId) {
        String token = java.util.UUID.randomUUID().toString();
        tokens.put(token, new Token(userId, orgId, Instant.now().plusSeconds(ttlSeconds), principal, customerId));
        return token;
    }

    @Override
    public void evictUser(Long userId) {
        tokens.entrySet().removeIf(e -> PRINCIPAL_USER.equals(e.getValue().principal())
                && userId.equals(e.getValue().userId()));
    }

    @Override
    public Token verify(String token) {
        Token t = tokens.get(token);
        if (t == null || t.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return t;
    }
}
