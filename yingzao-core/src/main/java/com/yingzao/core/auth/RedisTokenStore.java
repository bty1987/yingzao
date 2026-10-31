package com.yingzao.core.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis TokenStore(F2 认证 Redis 化,docs/15 R22~R23):{@code yingzao.auth.store=redis} 启用。
 * <ul>
 *   <li>会话键 {@code yz:auth:token:{token}} → JSON 载荷,TTL=会话期(签发即定不滑动;键过期即会话失效);</li>
 *   <li>踢会话索引 {@code yz:auth:user:{userId}} → token 集合(仅员工主体入索引;C 端 userId=0 不入,
 *       天然不受 evictUser 影响,与内存后端语义一致);</li>
 *   <li>签发走 Lua 单脚本原子写(SET+SADD+PEXPIRE,防「令牌已活而踢会话索引未落」的中断窗口);</li>
 *   <li>verify 读键 + expiresAt 双保险(键 TTL 兜底,防时钟偏移);连接异常失败关闭(鉴权宁拒勿放);</li>
 *   <li>键前缀 yz:auth: 与宿主机可能共存的其他 redis 使用者隔离,清理只扫该前缀。</li>
 * </ul>
 */
class RedisTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenStore.class);

    static final String TOKEN_PREFIX = "yz:auth:token:";
    static final String USER_INDEX_PREFIX = "yz:auth:user:";

    /** 签发原子脚本:SET 会话(PX=TTL);仅员工主体(principal=user)SADD 踢会话索引+PEXPIRE——
     *  C 端令牌(userId=0)不入索引,防 evictUser(0) 误杀 C 端会话(内存后端同语义)。 */
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            if ARGV[4] == 'user' then
                redis.call('SADD', KEYS[2], ARGV[3])
                redis.call('PEXPIRE', KEYS[2], ARGV[2])
            end
            return 1
            """, Long.class);

    /** 会话载荷:expiresAt 用 epoch 毫秒 long,零 JavaTime 序列化依赖。 */
    record Session(Long userId, Long orgId, Long customerId, String principal, long expiresAt) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper, long ttlHours) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Override
    public String issue(Long userId, Long orgId, String principal, Long customerId) {
        String token = UUID.randomUUID().toString();
        long expiresAt = Instant.now().plus(ttl).toEpochMilli();
        String payload = write(new Session(userId, orgId, customerId, principal, expiresAt));
        // 脚本参数必须全 String(StringRedisSerializer 序列化;传 Long 会 ClassCastException)
        redis.execute(ISSUE_SCRIPT,
                List.of(TOKEN_PREFIX + token, USER_INDEX_PREFIX + userId),
                payload, String.valueOf(ttl.toMillis()), token, principal);
        return token;
    }

    @Override
    public void evictUser(Long userId) {
        if (userId == null) {
            return;
        }
        String indexKey = USER_INDEX_PREFIX + userId;
        java.util.Set<String> tokens = redis.opsForSet().members(indexKey);
        if (tokens != null && !tokens.isEmpty()) {
            // 先删令牌后删索引:中断窗口只留悬空索引项(DEL 幂等,下次 evict 重放无害;令牌键自身 TTL 兜底)
            redis.delete(tokens.stream().map(t -> TOKEN_PREFIX + t).toList());
        }
        redis.delete(indexKey);
    }

    @Override
    public Token verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // redis 不可达=鉴权失败关闭(异常上抛 500,宁拒勿放);与内存后端「查无会话」语义不同,是后端故障不是会话无效
        String payload = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (payload == null) {
            return null;
        }
        Session s = read(payload);
        if (s == null || s.expiresAt() <= System.currentTimeMillis()) {
            return null;
        }
        return new Token(s.userId(), s.orgId(), Instant.ofEpochMilli(s.expiresAt()),
                s.principal(), s.customerId());
    }

    private String write(Session s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }

    private Session read(String payload) {
        try {
            return mapper.readValue(payload, Session.class);
        } catch (Exception e) {
            log.warn("token session payload unreadable, treated as invalid: {}", e.getMessage());
            return null;
        }
    }
}
