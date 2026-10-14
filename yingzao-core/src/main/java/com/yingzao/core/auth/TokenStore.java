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

    /** 主体类型:user(员工,yz_user)/ customer(C 端客户,customerprofile 档案)。 */
    public static final String PRINCIPAL_USER = "user";
    public static final String PRINCIPAL_CUSTOMER = "customer";

    public record Token(Long userId, Long orgId, Instant expiresAt, String principal, Long customerId) {

        /** 兼容旧构造(员工主体)。 */
        public Token(Long userId, Long orgId, Instant expiresAt) {
            this(userId, orgId, expiresAt, PRINCIPAL_USER, null);
        }
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public String issue(Long userId, Long orgId) {
        return issue(userId, orgId, PRINCIPAL_USER, null);
    }

    /** C 端令牌:principal=customer,userId 恒 0(员工账号体系之外的独立主体)。 */
    public String issueCustomer(Long orgId, Long customerId) {
        return issue(0L, orgId, PRINCIPAL_CUSTOMER, customerId);
    }

    public String issue(Long userId, Long orgId, String principal, Long customerId) {
        String token = java.util.UUID.randomUUID().toString();
        tokens.put(token, new Token(userId, orgId, Instant.now().plusSeconds(12 * 3600), principal, customerId));
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
