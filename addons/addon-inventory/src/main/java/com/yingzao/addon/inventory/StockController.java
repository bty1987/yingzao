package com.yingzao.addon.inventory;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 仓库档案/授权与库存查询端点。授权 = 可见性(库存查询按授权仓过滤,无授权空列表)。
 */
@RestController
@RequestMapping("/api/inventory")
public class StockController {

    public record WarehouseRequest(String code, String name, Integer status) {
    }

    public record GrantRequest(Long userId) {
    }

    private final WarehouseService warehouses;
    private final StockService stocks;

    public StockController(WarehouseService warehouses, StockService stocks) {
        this.warehouses = warehouses;
        this.stocks = stocks;
    }

    // ---- 仓库 ----

    @GetMapping("/warehouses")
    public ApiResponse<Object> listWarehouses(
            @RequestParam(required = false, defaultValue = "false") boolean includeDisabled,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(warehouses.list(orgId, includeDisabled));
    }

    @PostMapping("/warehouses")
    public ApiResponse<Long> createWarehouse(@RequestBody WarehouseRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(warehouses.create(orgId, body == null ? null : body.code(),
                body == null ? null : body.name(), userId));
    }

    @PutMapping("/warehouses/{id}")
    public ApiResponse<Void> updateWarehouse(@PathVariable long id, @RequestBody WarehouseRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        warehouses.update(orgId, id, body == null ? null : body.name(),
                body == null ? null : body.status());
        return ApiResponse.ok();
    }

    // ---- 授权 ----

    @GetMapping("/warehouses/{id}/users")
    public ApiResponse<Object> listUsers(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(warehouses.listUsers(orgId, id));
    }

    @PostMapping("/warehouses/{id}/users")
    public ApiResponse<Void> grant(@PathVariable long id, @RequestBody GrantRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        warehouses.grant(orgId, id, body == null ? null : body.userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/warehouses/{id}/users/{userId}")
    public ApiResponse<Void> revoke(@PathVariable long id, @PathVariable long userId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        warehouses.revoke(orgId, id, userId);
        return ApiResponse.ok();
    }

    // ---- 库存 ----

    @GetMapping("/stocks")
    public ApiResponse<Map<String, Object>> page(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(stocks.page(orgId, userId, warehouseId, keyword, page, size));
    }
}
