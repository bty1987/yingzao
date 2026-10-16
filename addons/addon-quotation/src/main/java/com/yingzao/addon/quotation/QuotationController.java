package com.yingzao.addon.quotation;

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
 * 报价端点:版本字典 + 报价单(建/分页/详情/费率覆盖)+ 定稿线(锁定/解锁)
 * + 审核线(提交/审核/完成)+ 切默认 + 功能区/功能项明细管理。
 * 金额一律厘;数量千分位;折扣万分比(10000=不打折)。
 */
@RestController
@RequestMapping("/api/quotation")
public class QuotationController {

    public record CreateRequest(Long orderId, String name, Long versionId, String remark) {
    }

    public record VersionRequest(String name, Integer busitype, Integer manageRate, Integer manageDiscount,
            Integer carryRate, Integer carryDiscount, Integer taxRate, Integer taxDiscount, String remark) {
    }

    public record StatusRequest(Integer status) {
    }

    public record PricingRequest(Integer discount, Integer manageRate, Integer manageDiscount, Integer carryRate,
            Integer carryDiscount, Integer taxRate, Integer taxDiscount) {
    }

    public record AreaRequest(String name, Integer type, Integer sort) {
    }

    public record ItemRequest(String name, Integer type, Integer subtype, String unit, Long num, Long price,
            Integer sort, String remark) {
    }

    private final QuotationService quotations;

    public QuotationController(QuotationService quotations) {
        this.quotations = quotations;
    }

    // ---- 版本字典 ----

    @GetMapping("/versions")
    public ApiResponse<Object> listVersions(@RequestParam(required = false, defaultValue = "false") boolean includeDisabled,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(quotations.listVersions(orgId, includeDisabled));
    }

    @PostMapping("/versions")
    public ApiResponse<Long> createVersion(@RequestBody VersionRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(quotations.createVersion(orgId, toVersionCmd(body), userId));
    }

    @PutMapping("/versions/{id}")
    public ApiResponse<Void> updateVersion(@PathVariable long id, @RequestBody VersionRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.updateVersion(orgId, id, toVersionCmd(body), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/versions/{id}/status")
    public ApiResponse<Void> updateVersionStatus(@PathVariable long id, @RequestBody StatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.updateVersionStatus(orgId, id, body == null ? null : body.status(), userId);
        return ApiResponse.ok();
    }

    // ---- 报价单 ----

    @GetMapping("/quotations")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Integer status, @RequestParam(required = false) Integer attstatus,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(quotations.page(orgId, orderId, orderNo, status, attstatus, page, size));
    }

    @PostMapping("/quotations")
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(quotations.create(orgId, new QuotationService.CreateCmd(
                body == null || body.orderId() == null ? 0 : body.orderId(),
                body == null ? null : body.name(),
                body == null ? null : body.versionId(),
                body == null ? null : body.remark()), userId));
    }

    @GetMapping("/quotations/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(quotations.detail(orgId, id));
    }

    /** 挂/换定材包(B 类#7):body.packageId 空=解绑;仅编辑中报价可改。 */
    public record MaterialPackageRequest(Long packageId) {
    }

    @PostMapping("/quotations/{id}/material-package")
    public ApiResponse<Void> setMaterialPackage(@PathVariable long id,
            @RequestBody MaterialPackageRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.setMaterialPackage(orgId, id, body == null ? null : body.packageId(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/pricing")
    public ApiResponse<Void> updatePricing(@PathVariable long id, @RequestBody PricingRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.updatePricing(orgId, id, body == null ? null
                : new QuotationService.PricingCmd(body.discount(), body.manageRate(), body.manageDiscount(),
                        body.carryRate(), body.carryDiscount(), body.taxRate(), body.taxDiscount()), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/lock")
    public ApiResponse<Void> lock(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.lock(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/unlock")
    public ApiResponse<Void> unlock(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.unlock(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/submit")
    public ApiResponse<Void> submit(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.submit(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/audit")
    public ApiResponse<Void> audit(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.audit(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.finish(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.setDefault(orgId, id, userId);
        return ApiResponse.ok();
    }

    // ---- 功能区/功能项明细 ----

    @PostMapping("/quotations/{id}/areas")
    public ApiResponse<Long> addArea(@PathVariable long id, @RequestBody AreaRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(quotations.addArea(orgId, id, body == null ? null
                : new QuotationService.AreaCmd(body.name(), body.type(), body.sort()), userId));
    }

    @PutMapping("/quotations/{id}/areas/{areaId}")
    public ApiResponse<Void> updateArea(@PathVariable long id, @PathVariable long areaId,
            @RequestBody AreaRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.updateArea(orgId, id, areaId, body == null ? null
                : new QuotationService.AreaCmd(body.name(), body.type(), body.sort()), userId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/quotations/{id}/areas/{areaId}")
    public ApiResponse<Void> deleteArea(@PathVariable long id, @PathVariable long areaId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.deleteArea(orgId, id, areaId, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/quotations/{id}/areas/{areaId}/items")
    public ApiResponse<Long> addItem(@PathVariable long id, @PathVariable long areaId,
            @RequestBody ItemRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(quotations.addItem(orgId, id, areaId, body == null ? null
                : new QuotationService.ItemCmd(body.name(), body.type(), body.subtype(), body.unit(),
                        body.num(), body.price(), body.sort(), body.remark()), userId));
    }

    @PutMapping("/quotations/{id}/items/{itemId}")
    public ApiResponse<Void> updateItem(@PathVariable long id, @PathVariable long itemId,
            @RequestBody ItemRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.updateItem(orgId, id, itemId, body == null ? null
                : new QuotationService.ItemCmd(body.name(), body.type(), body.subtype(), body.unit(),
                        body.num(), body.price(), body.sort(), body.remark()), userId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/quotations/{id}/items/{itemId}")
    public ApiResponse<Void> deleteItem(@PathVariable long id, @PathVariable long itemId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        quotations.deleteItem(orgId, id, itemId, userId);
        return ApiResponse.ok();
    }

    private static QuotationService.VersionCmd toVersionCmd(VersionRequest body) {
        if (body == null) {
            return null;
        }
        return new QuotationService.VersionCmd(body.name(), body.busitype(), body.manageRate(),
                body.manageDiscount(), body.carryRate(), body.carryDiscount(), body.taxRate(),
                body.taxDiscount(), body.remark());
    }
}
