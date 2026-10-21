package com.yingzao.core.org;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 组织树管理端点(内核平台设施,仅管理员 122003,对齐 roles/plans——内核端点自带守卫)。
 * 树查询/建子组织/改名改标签/停启(停=子树级联,启用只恢复本节点)。
 */
@RestController
public class OrganizationController {

    private final OrganizationService orgs;
    private final OrgScopeService scopes;
    private final RoleService roleService;

    public OrganizationController(OrganizationService orgs, OrgScopeService scopes, RoleService roleService) {
        this.orgs = orgs;
        this.scopes = scopes;
        this.roleService = roleService;
    }

    @GetMapping("/api/orgs")
    public ApiResponse<?> tree(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        return ApiResponse.ok(orgs.tree());
    }

    @PostMapping("/api/orgs")
    public ApiResponse<Long> create(@RequestBody OrganizationService.CreateBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        Long id = orgs.create(orgId, body, userId);
        scopes.evictAll();
        return ApiResponse.ok(id);
    }

    @PostMapping("/api/orgs/{id}/update")
    public ApiResponse<Void> update(@PathVariable long id,
            @RequestBody OrganizationService.UpdateBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        orgs.update(orgId, id, body);
        scopes.evictAll();
        return ApiResponse.ok();
    }

    public record StatusBody(Integer status) {
    }

    @PostMapping("/api/orgs/{id}/status")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        int status = body == null || body.status() == null ? 0 : body.status();
        orgs.changeStatus(orgId, id, status);
        scopes.evictAll();
        return ApiResponse.ok();
    }
}
