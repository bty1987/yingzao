package com.yingzao.addon.inventory;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 采购入库端点:建单(挂销售单锚点可空)/分批入库(自动判定部分/全部)/删项/取消
 * (余量释放走 StockPurchaseChangedEvent,销售域订阅回写)。
 */
@RestController
@RequestMapping("/api/inventory/purchases")
public class PurchaseController {

    public record CreateRequest(Long salesId, Long supplierId,
            List<PurchaseService.ItemCmd> items, String remark) {
    }

    public record InboundRequest(Long warehouseId, List<PurchaseService.InboundLineCmd> lines) {
    }

    private final PurchaseService purchases;

    public PurchaseController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(purchases.page(orgId, status, code, page, size));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(purchases.create(orgId, body == null ? null : body.salesId(),
                body == null ? null : body.supplierId(), body == null ? null : body.items(),
                body == null ? null : body.remark(), userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(purchases.detail(orgId, id));
    }

    @PostMapping("/{id}/inbound")
    public ApiResponse<Long> inbound(@PathVariable long id, @RequestBody InboundRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(purchases.inbound(orgId, id, body == null ? null : body.warehouseId(),
                body == null ? null : body.lines(), userId));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ApiResponse<Void> removeItem(@PathVariable long id, @PathVariable long itemId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        purchases.removeItem(orgId, id, itemId, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        purchases.cancel(orgId, id, userId);
        return ApiResponse.ok();
    }
}
