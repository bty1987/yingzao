package com.yingzao.addon.inventory;

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
 * 领料管理端点:创建(工程额度双闸经 ConstructionAccessoryApi)/预出/出库(AS 货单派生)/
 * 到货/撤销/拒绝(额度回退)/退回(AR 额度回冲)。
 */
@RestController
@RequestMapping("/api/inventory/requisitions")
public class RequisitionController {

    public record CreateRequest(Long orderId, Integer type, List<RequisitionService.ItemCmd> items,
            String comment, String fetchTime) {
    }

    public record ReturnRequest(List<RequisitionService.ReturnLineCmd> lines) {
    }

    private final RequisitionService requisitions;

    public RequisitionController(RequisitionService requisitions) {
        this.requisitions = requisitions;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Integer status, @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(requisitions.page(orgId, orderId, status, type, page, size));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(requisitions.create(orgId, body == null ? null : body.orderId(),
                body == null ? null : body.type(), body == null ? null : body.items(),
                body == null ? null : body.comment(), body == null ? null : body.fetchTime(), userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(requisitions.detail(orgId, id));
    }

    @PostMapping("/{id}/preout")
    public ApiResponse<Void> preout(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        requisitions.preout(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/outbound")
    public ApiResponse<Void> outbound(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        requisitions.outbound(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/arrive")
    public ApiResponse<Void> arrive(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        requisitions.arrive(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        requisitions.close(orgId, id, false, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/refuse")
    public ApiResponse<Void> refuse(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        requisitions.close(orgId, id, true, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/returns")
    public ApiResponse<Long> returnGoods(@PathVariable long id, @RequestBody ReturnRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(requisitions.returnGoods(orgId, id,
                body == null ? null : body.lines(), userId));
    }
}
