package com.yingzao.addon.payment;

import java.util.List;
import java.util.Map;

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
 * 款项单端点:创建/收款(渠道行合计平衡)/确认/退款(走内核审批)/作废/分页/详情。
 * 部分收款等扩展形态 V2;本域金额一律厘。
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentBillController {

    public record CreateBillRequest(String bizAddon, String bizType, String bizId, String typeCode,
            Long amount, String remark) {
    }

    public record CollectRequest(List<BillService.ChannelLine> channels) {
    }

    public record RefundRequest(Long assigneeId, String reason) {
    }

    private final BillService bills;

    public PaymentBillController(BillService bills) {
        this.bills = bills;
    }

    @GetMapping("/bills")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String bizAddon,
            @RequestParam(required = false) String bizType, @RequestParam(required = false) String bizId,
            @RequestParam(required = false) Integer status, @RequestParam(required = false) Integer direction,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(bills.page(orgId, bizAddon, bizType, bizId, status, direction, page, size));
    }

    @GetMapping("/bills/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(bills.detail(orgId, id));
    }

    @PostMapping("/bills")
    public ApiResponse<Long> create(@RequestBody CreateBillRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(bills.createBill(orgId, body.bizAddon(), body.bizType(), body.bizId(),
                body.typeCode(), body.amount() == null ? 0 : body.amount(), body.remark(), userId));
    }

    @PostMapping("/bills/{id}/collect")
    public ApiResponse<Map<String, Object>> collect(@PathVariable long id, @RequestBody CollectRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(bills.collect(orgId, id, body.channels(), userId));
    }

    @PostMapping("/bills/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        bills.confirm(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/bills/{id}/refund")
    public ApiResponse<Long> refund(@PathVariable long id, @RequestBody RefundRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(bills.submitRefund(orgId, id, userId,
                body.assigneeId(), body.reason()));
    }

    @PostMapping("/bills/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        bills.cancel(orgId, id);
        return ApiResponse.ok();
    }
}
