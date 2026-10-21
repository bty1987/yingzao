package com.yingzao.core.org;

import java.util.List;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 组织数据范围基座(docs/08 S1):登录节点的可见组织 id 集(自身+子树),
 * Caffeine 缓存 60s + 组织变更主动全逐出(对齐 RoleService 授权快照姿势)。
 * S2 数据范围查询基座与 S3 目标聚合复用本服务,不在各域重复推导。
 */
@Service
public class OrgScopeService {

    private final OrganizationService orgs;
    private final Cache<Long, List<Long>> cache = Caffeine.newBuilder()
            .expireAfterWrite(java.time.Duration.ofSeconds(60))
            .build();

    public OrgScopeService(OrganizationService orgs) {
        this.orgs = orgs;
    }

    /** 登录节点可见组织 id 集(自身+启用子树);缓存 60s,组织变更即逐出。 */
    public List<Long> scopeOrgIds(long orgId) {
        return cache.get(orgId, orgs::scopeOrgIds);
    }

    /** 组织建/停/启后全逐出(低频管理动作,全量逐出语义最简)。 */
    public void evictAll() {
        cache.invalidateAll();
    }
}
