package com.yingzao.core.auth;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.context.YzContext;
import com.yingzao.core.rbac.RbacInterceptor;
import com.yingzao.core.rbac.RbacService;
import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    public static final int CODE_UNAUTHORIZED = 110002;

    // /api/addons 不在白名单:下发内容按登录人角色过滤(RBAC),菜单前置拉取不存在
    private static final List<String> WHITELIST = List.of(
            "/api/auth/login", "/api/c/auth/", "/api/c/cases", "/error", "/actuator/health");

    private final TokenStore tokenStore;
    private final MessageSource messages;
    private final ObjectMapper objectMapper;
    private final RbacService rbac;
    private final RoleService roleService;

    public AuthWebConfig(TokenStore tokenStore, MessageSource messages, ObjectMapper objectMapper,
            RbacService rbac, RoleService roleService) {
        this.tokenStore = tokenStore;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.rbac = rbac;
        this.roleService = roleService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor()).addPathPatterns("/api/**");
        // RBAC v2 端点鉴权:登录态之后同链(order 默认按注册序,Auth 在前)
        registry.addInterceptor(new RbacInterceptor(rbac, roleService, messages, objectMapper))
                .addPathPatterns("/api/**");
    }

    class AuthInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            if (WHITELIST.stream().anyMatch(request.getRequestURI()::startsWith)) {
                return true;
            }
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            TokenStore.Token t = token == null ? null : tokenStore.verify(token);
            if (t == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(response.getWriter(), ApiResponse.error(CODE_UNAUTHORIZED,
                        messages.getMessage("auth.unauthorized", null, LocaleContextHolder.getLocale())));
                return false;
            }
            request.setAttribute("yz.orgId", t.orgId());
            if (TokenStore.PRINCIPAL_CUSTOMER.equals(t.principal())) {
                // C 端主体:不写 yz.userId(RBAC 按 principal=customer 单独归类,防越权面)
                request.setAttribute("yz.principal", TokenStore.PRINCIPAL_CUSTOMER);
                request.setAttribute("yz.customerId", t.customerId());
            } else {
                request.setAttribute("yz.userId", t.userId());
                YzContext.set(t.userId(), t.orgId());
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
            YzContext.clear();
        }
    }
}
