package com.yingzao.addon.sales;

import java.util.List;
import java.util.Map;

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
 * 销售单主链端点:开单/改单/款项/客户确认/完结/废单/退货/配送。
 * 金额入参全部厘,数量千分位;前端金额一律不被信任(服务端重算)。
 */
@RestController
@RequestMapping("/api/sales")
public class SalesController {

    public record CreateRequest(Long orderId, List<SalesOrderService.ItemCmd> items, String remark) {
    }

    public record ItemsRequest(List<SalesOrderService.ItemCmd> items) {
    }

    public record VerifyCodeRequest(String phone) {
    }

    public record ConfirmRequest(String phone, String code) {
    }

    public record RefundRequest(Integer type, List<SalesRefundService.RefundItemCmd> items,
                                Long assigneeId, String remark) {
    }

    public record DeliveryRequest(List<SalesDeliveryService.DeliveryItemCmd> items, String remark) {
    }

    public record AdvanceRequest(String action) {
    }

    private final SalesOrderService orders;
    private final SalesRefundService refunds;
    private final SalesDeliveryService deliveries;

    public SalesController(SalesOrderService orders, SalesRefundService refunds,
            SalesDeliveryService deliveries) {
        this.orders = orders;
        this.refunds = refunds;
        this.deliveries = deliveries;
    }

    // ---- 销售单 ----

    @PostMapping("/orders")
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(orders.create(orgId, body == null || body.orderId() == null ? 0
                : body.orderId(), body == null ? null : body.items(),
                body == null ? null : body.remark(), userId));
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> page(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer balanceStatus,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(orders.page(orgId, code, orderId, status, balanceStatus, page, size));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Map<String, Object> detail = orders.detail(orgId, id);
        detail.put("deliveryItems", orders.deliveryItems(orgId, id));
        return ApiResponse.ok(detail);
    }

    @PutMapping("/orders/{id}/items")
    public ApiResponse<Long> updateItems(@PathVariable long id, @RequestBody ItemsRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(orders.updateItems(orgId, id, body == null ? null : body.items(), userId));
    }

    @PostMapping("/orders/{id}/bill")
    public ApiResponse<Void> generateBill(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        orders.generateBill(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/verify-code")
    public ApiResponse<Void> sendVerifyCode(@PathVariable long id,
            @RequestBody VerifyCodeRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        orders.sendVerifyCode(orgId, id, body == null ? null : body.phone(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, @RequestBody ConfirmRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        orders.confirm(orgId, id, body == null ? null : body.phone(), body == null ? null : body.code());
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        orders.finish(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/terminate")
    public ApiResponse<Void> terminate(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        orders.terminate(orgId, id, userId);
        return ApiResponse.ok();
    }

    // ---- 退货 ----

    @PostMapping("/orders/{id}/refunds")
    public ApiResponse<Long> initiateRefund(@PathVariable long id, @RequestBody RefundRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(refunds.initiate(orgId, id, body == null || body.type() == null ? 0
                : body.type(), body == null ? null : body.items(),
                body == null ? null : body.assigneeId(), body == null ? null : body.remark(), userId));
    }

    @GetMapping("/refunds")
    public ApiResponse<Map<String, Object>> pageRefunds(
            @RequestParam(required = false) Long saleId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(refunds.page(orgId, saleId, status, type, page, size));
    }

    @GetMapping("/refunds/{id}")
    public ApiResponse<Map<String, Object>> refundDetail(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(refunds.detail(orgId, id));
    }

    @PostMapping("/refunds/{id}/verify-code")
    public ApiResponse<Void> sendRefundVerifyCode(@PathVariable long id,
            @RequestBody VerifyCodeRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        refunds.sendVerifyCode(orgId, id, body == null ? null : body.phone(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/refunds/{id}/confirm")
    public ApiResponse<Void> confirmRefund(@PathVariable long id, @RequestBody ConfirmRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        refunds.confirm(orgId, id, body == null ? null : body.phone(), body == null ? null : body.code());
        return ApiResponse.ok();
    }

    // ---- 配送 ----

    @PostMapping("/orders/{id}/deliveries")
    public ApiResponse<Long> createDelivery(@PathVariable long id, @RequestBody DeliveryRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(deliveries.create(orgId, id, body == null ? null : body.items(),
                body == null ? null : body.remark(), userId));
    }

    @GetMapping("/deliveries")
    public ApiResponse<Map<String, Object>> pageDeliveries(
            @RequestParam(required = false) Long saleId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(deliveries.page(orgId, saleId, page, size));
    }

    @GetMapping("/deliveries/{id}")
    public ApiResponse<Map<String, Object>> deliveryDetail(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(deliveries.detail(orgId, id));
    }

    @PostMapping("/delivery-items/{id}/advance")
    public ApiResponse<Void> advanceDelivery(@PathVariable long id, @RequestBody AdvanceRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        deliveries.advance(orgId, id, body == null ? null : body.action(), userId);
        return ApiResponse.ok();
    }
}
