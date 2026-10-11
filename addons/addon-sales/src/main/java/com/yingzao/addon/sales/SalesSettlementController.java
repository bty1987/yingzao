package com.yingzao.addon.sales;

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
 * 结算工作台端点(跨单):改厂结值/厂结/改结算值/结算/整单驳回预结。
 */
@RestController
@RequestMapping("/api/sales")
public class SalesSettlementController {

    public record ValueRequest(Long value) {
    }

    public record RejectRequest(String reason) {
    }

    private final SalesSettlementService settlements;

    public SalesSettlementController(SalesSettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/settlements")
    public ApiResponse<Map<String, Object>> page(
            @RequestParam(required = false) String saleCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(settlements.page(orgId, saleCode, status, type, page, size));
    }

    @PutMapping("/settlements/{id}/vbalance")
    public ApiResponse<Void> updateVbalance(@PathVariable long id, @RequestBody ValueRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        settlements.updateVbalance(orgId, id, body == null || body.value() == null ? 0 : body.value(),
                userId);
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/vpost")
    public ApiResponse<Void> vpost(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        settlements.vpost(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PutMapping("/settlements/{id}/pbalance")
    public ApiResponse<Void> updatePbalance(@PathVariable long id, @RequestBody ValueRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        settlements.updatePbalance(orgId, id, body == null || body.value() == null ? 0 : body.value(),
                userId);
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/post")
    public ApiResponse<Void> post(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        settlements.post(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/settlement-reject")
    public ApiResponse<Void> reject(@PathVariable long id, @RequestBody RejectRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        settlements.reject(orgId, id, body == null ? null : body.reason(), userId);
        return ApiResponse.ok();
    }
}
