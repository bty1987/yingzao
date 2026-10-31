package com.yingzao.core.auth;

import java.time.Instant;

/**
 * 认证会话存储抽象(F2 认证 Redis 化,docs/15 R22~R23)。
 * <p>双后端:内存缺省(spike 语义原样,单实例)/ Redis 可选(生产与水平扩展,
 * {@code yingzao.auth.store=redis} 一行切换)。语义两不变:</p>
 * <ul>
 *   <li>会话固定 12h TTL,签发即定不滑动(旧令牌到期自然失效);</li>
 *   <li>{@link #evictUser} 踢出员工账号全部在管会话(停用/重置密码即时生效;C 端令牌不受影响)。</li>
 * </ul>
 */
public interface TokenStore {

    /** 主体类型:user(员工,yz_user)/ customer(C 端客户,customerprofile 档案)。 */
    String PRINCIPAL_USER = "user";
    String PRINCIPAL_CUSTOMER = "customer";

    record Token(Long userId, Long orgId, Instant expiresAt, String principal, Long customerId) {

        /** 兼容旧构造(员工主体)。 */
        public Token(Long userId, Long orgId, Instant expiresAt) {
            this(userId, orgId, expiresAt, PRINCIPAL_USER, null);
        }
    }

    default String issue(Long userId, Long orgId) {
        return issue(userId, orgId, PRINCIPAL_USER, null);
    }

    /** C 端令牌:principal=customer,userId 恒 0(员工账号体系之外的独立主体)。 */
    default String issueCustomer(Long orgId, Long customerId) {
        return issue(0L, orgId, PRINCIPAL_CUSTOMER, customerId);
    }

    String issue(Long userId, Long orgId, String principal, Long customerId);

    /** 踢出员工账号全部在管会话(停用/重置密码即时生效;C 端令牌 userId=0 不受影响)。 */
    void evictUser(Long userId);

    Token verify(String token);
}
