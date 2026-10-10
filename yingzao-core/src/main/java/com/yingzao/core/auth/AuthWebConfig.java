package com.yingzao.core.auth;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    public static final int CODE_UNAUTHORIZED = 110002;

    private static final List<String> WHITELIST = List.of(
            "/api/auth/login", "/api/addons", "/error", "/actuator/health");

    private final TokenStore tokenStore;
    private final MessageSource messages;
    private final ObjectMapper objectMapper;

    public AuthWebConfig(TokenStore tokenStore, MessageSource messages, ObjectMapper objectMapper) {
        this.tokenStore = tokenStore;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor()).addPathPatterns("/api/**");
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
            request.setAttribute("yz.userId", t.userId());
            request.setAttribute("yz.orgId", t.orgId());
            return true;
        }
    }
}
