package com.yingzao.core.rbac;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 角色与授权管理端点(内核平台设施):全部仅管理员可操作(RBAC 自守卫,122003)。
 * 授权语义 = 全量替换(传入集合即目标态),幂等可重放。
 */
@RestController
public class RoleController {

    public record CreateRequest(String code, String name, String remark) {
    }

    public record MenusRequest(List<String> menus) {
    }

    public record CapabilitiesRequest(List<String> capabilities) {
    }

    public record UsersRequest(List<Long> userIds) {
    }

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/api/roles")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        return ApiResponse.ok(roleService.list(orgId));
    }

    @PostMapping("/api/roles")
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        return ApiResponse.ok(roleService.create(
                new RoleService.CreateCmd(orgId, body.code(), body.name(), body.remark())));
    }

    @GetMapping("/api/roles/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        return ApiResponse.ok(roleService.detail(orgId, id));
    }

    @DeleteMapping("/api/roles/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        roleService.delete(orgId, id);
        return ApiResponse.ok();
    }

    @PutMapping("/api/roles/{id}/menus")
    public ApiResponse<Void> updateMenus(@PathVariable long id, @RequestBody MenusRequest body,
                                         HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        roleService.updateMenus(orgId, id, body.menus());
        return ApiResponse.ok();
    }

    @PutMapping("/api/roles/{id}/capabilities")
    public ApiResponse<Void> updateCapabilities(@PathVariable long id, @RequestBody CapabilitiesRequest body,
                                                HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        roleService.updateCapabilities(orgId, id, body.capabilities());
        return ApiResponse.ok();
    }

    @PutMapping("/api/roles/{id}/users")
    public ApiResponse<Void> updateUsers(@PathVariable long id, @RequestBody UsersRequest body,
                                         HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        roleService.requireAdmin(orgId, userId);
        roleService.updateUsers(orgId, id, body.userIds());
        return ApiResponse.ok();
    }
}
