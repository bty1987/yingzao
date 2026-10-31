package com.yingzao.core.org;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.org.OrganizationService.CreateBody;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 数据范围基座缓存语义单测(F4a,docs/15 R26~R27):Caffeine 60s + 组织变更全逐出——
 * 组织建/停/启后的可见性时效契约(AuthInterceptor 每请求注入 yz.orgScope 依赖此语义)。
 */
class OrgScopeServiceTest extends KernelTestBase {

    @Test
    void scopeCachedUntilEvicted() {
        OrganizationService orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        OrgScopeService scope = new OrgScopeService(orgs);
        orgs.rowOf(1);
        // 首查装入缓存
        List<Long> first = scope.scopeOrgIds(1);
        assertEquals(List.of(1L), first);
        // 组织变更(建子组织)——缓存未逐出前可见性不变
        long child = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        assertEquals(List.of(1L), scope.scopeOrgIds(1), "缓存期内不得看到新建子组织");
        // 全量逐出后可见
        scope.evictAll();
        assertEquals(List.of(1L, child), scope.scopeOrgIds(1));
    }
}
