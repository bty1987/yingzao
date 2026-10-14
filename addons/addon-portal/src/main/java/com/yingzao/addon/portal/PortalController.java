package com.yingzao.addon.portal;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * C 端门户端点(/api/c/**,员工管理端之外的第二 API 面):
 * - 发码/登录/案例 = anonymous(内核白名单 /api/c/auth/ + addon.yml anonymous 型);
 * - 进度/积分 = c-customer(RbacInterceptor 按 principal=customer 归类,
 *   yz.customerId 为资源归属校验锚;员工令牌调本组端点一律 122006 拒)。
 */
@RestController
@RequestMapping("/api/c")
public class PortalController {

    public record CodeRequest(String mobile) {
    }

    public record LoginRequest(String mobile, String code) {
    }

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @PostMapping("/auth/code")
    public ApiResponse<Void> sendCode(@RequestBody CodeRequest body) {
        portal.sendCode(portal.anonymousOrgId(), body == null ? null : body.mobile());
        return ApiResponse.ok();
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest body) {
        return ApiResponse.ok(portal.login(portal.anonymousOrgId(),
                body == null ? null : body.mobile(), body == null ? null : body.code()));
    }

    @GetMapping("/cases")
    public ApiResponse<Map<String, Object>> cases(@RequestParam(required = false) String city,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(portal.cases(portal.anonymousOrgId(), city, page, size));
    }

    @GetMapping("/progress")
    public ApiResponse<Map<String, Object>> progress(@RequestParam long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long customerId = (Long) request.getAttribute("yz.customerId");
        return ApiResponse.ok(portal.progress(orgId, customerId, orderId));
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> myOrders(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long customerId = (Long) request.getAttribute("yz.customerId");
        return ApiResponse.ok(portal.myOrders(orgId, customerId));
    }

    @GetMapping("/points")
    public ApiResponse<Object> points(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long customerId = (Long) request.getAttribute("yz.customerId");
        return ApiResponse.ok(portal.points(orgId, customerId));
    }
}
