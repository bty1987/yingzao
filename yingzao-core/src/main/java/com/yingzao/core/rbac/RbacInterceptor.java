package com.yingzao.core.rbac;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import com.yingzao.core.auth.TokenStore;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RBAC v2 端点鉴权拦截器(内核平台设施):注册在认证拦截器之后,只拦 /api/**。
 * 流程 = RbacService.match(uri, method)(pattern 命中但 methods 不兼容的规则跳过继续找,
 * 全不兼容 = 未声明)→ 未命中按模式(observe 放行+按 URI 去重审计 / enforce 拒 122006)
 * → 命中按 require 归类:authenticated 放行 / 菜单 path 查授权并集 / capability 查功能点并集,
 * 管理员旁路。授权快照走 RoleService 缓存(TTL 60s + 变更主动逐出,无陈旧放行窗口)。
 * 修 v1 缺口「拿到任何合法 token 即可调任意 /api/**」——服务端守卫才是真闸门。
 */
public class RbacInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RbacInterceptor.class);

    /** observe 期未声明 API 审计去重(进程内;重启重告,防高流量刷屏)。 */
    private static final Set<String> AUDITED = ConcurrentHashMap.newKeySet();

    private final RbacService rbac;
    private final RoleService roles;
    private final MessageSource messages;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RbacInterceptor(RbacService rbac, RoleService roles, MessageSource messages,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.rbac = rbac;
        this.roles = roles;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // C 端主体(principal=customer):只允许显式 require=c-customer 的端点,
        // 其余(含未声明/员工端点)一律拒——越权面收口在拦截器一处,防 C 端令牌横调员工 API。
        if (TokenStore.PRINCIPAL_CUSTOMER.equals(request.getAttribute("yz.principal"))) {
            String cUri = request.getRequestURI();
            var cHit = rbac.match(cUri, request.getMethod());
            if (cHit.isPresent() && "c-customer".equalsIgnoreCase(cHit.get().require())) {
                return true;
            }
            return deny(response, cUri, "c-customer");
        }

        // 白名单请求(login/health/error)未过认证拦截器,无 yz.userId → RBAC 不适用,直接放行
        Object uidAttr = request.getAttribute("yz.userId");
        if (!(uidAttr instanceof Long)) {
            return true;
        }
        long userId = (Long) uidAttr;
        long orgId = (Long) request.getAttribute("yz.orgId");
        String uri = request.getRequestURI();

        var hit = rbac.match(uri, request.getMethod());
        if (hit.isEmpty()) {
            // 未命中:内核端点豁免(自带守卫);插件 API 按 mode——observe 放行+审计 / enforce 拒
            if (!rbac.isPluginUri(uri)) {
                return true;
            }
            RoleService.Grants grants = roles.grantsCached(orgId, userId);
            if (grants.admin()) {
                return true;
            }
            if (rbac.enforce()) {
                return deny(response, uri, "(undeclared)");
            }
            if (AUDITED.add(uri)) {
                log.warn("rbac observe: undeclared api {} by user {} - add an api rule in addon.yml", uri, userId);
            }
            return true;
        }

        RbacService.ApiRule rule = hit.get();
        // method 兼容性已在 match 内判定(pattern 命中但 methods 不兼容的规则已跳过),命中即可归类
        if ("authenticated".equalsIgnoreCase(rule.require())
                || "anonymous".equalsIgnoreCase(rule.require())) {
            // anonymous:免认证端点(声明即文档);员工令牌/匿名请求一视同仁放行
            return true;
        }
        if ("c-customer".equalsIgnoreCase(rule.require())) {
            // C 端专属端点:员工主体(含 admin)一律拒——语义上不属管理面(设计稿 §3)
            return deny(response, uri, "c-customer");
        }

        RoleService.Grants grants = roles.grantsCached(orgId, userId);
        if (grants.admin()) {
            return true;
        }
        boolean allowed = rule.require().startsWith("/")
                ? grants.menus().contains(rule.require())
                : grants.capabilities().contains(rule.require());
        if (!allowed) {
            return deny(response, uri, rule.require());
        }
        return true;
    }

    private boolean deny(HttpServletResponse response, String uri, String require) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        String text = messages.getMessage("rbac.api.forbidden", new Object[] { require },
                LocaleContextHolder.getLocale());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(RoleService.CODE_API_FORBIDDEN, text));
        log.info("rbac deny: {} require={} ", uri, require);
        return false;
    }

    /** 供测试/管理端观察:已审计 URI 清单。 */
    public static List<String> auditedUris() {
        return List.copyOf(AUDITED);
    }
}
