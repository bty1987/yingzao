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
 * 外辅采购端点(协调员视角):登记(工程验收后锁定)/分配厂商/送货/到货/收费明细/
 * 结算/反结算/完成/作废(仅到货前)。
 */
@RestController
@RequestMapping("/api/inventory/outsourcings")
public class OutsourcingController {

    public record CreateRequest(Long orderId, List<OutsourcingService.ItemCmd> items,
            Integer isEnter, Integer isLift, Integer isExceed, Integer isAttic, Integer loadFloor,
            String remark) {
    }

    public record AssignRequest(String supplierName, String supplierPhone) {
    }

    public record QtyRequest(List<OutsourcingService.QtyLineCmd> lines) {
    }

    public record ChargesRequest(List<OutsourcingService.ChargeCmd> charges) {
    }

    public record CancelRequest(String remark) {
    }

    private final OutsourcingService outsourcings;

    public OutsourcingController(OutsourcingService outsourcings) {
        this.outsourcings = outsourcings;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String supplierName,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(outsourcings.page(orgId, orderId, status, supplierName, page, size));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(outsourcings.create(orgId, body == null ? null : body.orderId(),
                body == null ? null : body.items(), body == null ? null : body.isEnter(),
                body == null ? null : body.isLift(), body == null ? null : body.isExceed(),
                body == null ? null : body.isAttic(), body == null ? null : body.loadFloor(),
                body == null ? null : body.remark(), userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(outsourcings.detail(orgId, id));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<Void> assign(@PathVariable long id, @RequestBody AssignRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.assign(orgId, id, body == null ? null : body.supplierName(),
                body == null ? null : body.supplierPhone(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/deliver")
    public ApiResponse<Void> deliver(@PathVariable long id, @RequestBody QtyRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.deliver(orgId, id, body == null ? null : body.lines(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/receive")
    public ApiResponse<Void> receive(@PathVariable long id, @RequestBody QtyRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.receive(orgId, id, body == null ? null : body.lines(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/charges")
    public ApiResponse<Void> saveCharges(@PathVariable long id, @RequestBody ChargesRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.saveCharges(orgId, id, body == null ? null : body.charges(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/balance")
    public ApiResponse<Void> balance(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.balance(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reject-balance")
    public ApiResponse<Void> rejectBalance(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.rejectBalance(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.finish(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, @RequestBody CancelRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        outsourcings.cancel(orgId, id, body == null ? null : body.remark(), userId);
        return ApiResponse.ok();
    }
}
