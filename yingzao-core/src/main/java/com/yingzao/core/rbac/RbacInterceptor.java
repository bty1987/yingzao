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

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RBAC v2 端点鉴权拦截器(内核平台设施):注册在认证拦截器之后,只拦 /api/**。
 * 流程 = RbacService.match(uri) → 未命中按模式(observe 放行+按 URI 去重审计 / enforce 拒 122006)
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
        // 白名单请求(login/health/error)未过认证拦截器,无 yz.userId → RBAC 不适用,直接放行
        Object uidAttr = request.getAttribute("yz.userId");
        if (!(uidAttr instanceof Long)) {
            return true;
        }
        long userId = (Long) uidAttr;
        long orgId = (Long) request.getAttribute("yz.orgId");
        String uri = request.getRequestURI();

        var hit = rbac.match(uri);
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
        if (rule.methods() != null && !rule.methods().isEmpty()
                && !rule.methods().contains(request.getMethod())) {
            return true;
        }
        if ("authenticated".equalsIgnoreCase(rule.require())) {
            return true;
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
