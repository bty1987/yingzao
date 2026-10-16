package com.yingzao.addon.inventory;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 库存组织级配置端点(定材校验开关,B 类#7):GET 回显 / POST 更新。
 * RBAC 归 /api/inventory prefix 菜单闸(addon.yml 既有声明,零新增)。
 */
@RestController
@RequestMapping("/api/inventory/config")
public class InventoryConfigController {

    public record QuotaCheckBody(Integer enabled) {
    }

    private final RequisitionService requisitions;

    public InventoryConfigController(RequisitionService requisitions) {
        this.requisitions = requisitions;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(requisitions.configOf(orgId));
    }

    @PostMapping
    public ApiResponse<Void> save(@RequestBody QuotaCheckBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        requisitions.saveQuotaCheck(orgId, body == null ? null : body.enabled());
        return ApiResponse.ok();
    }
}
