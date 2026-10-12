package com.yingzao.addon.supplier;

import java.util.List;
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
 * 厂商主数据端点:建档/检索/编辑/启停/ext_no 幂等 upsert(外部同步通道)/
 * 品牌关联(绑/解/设直属)/三层结算条款(品牌 upsert+品类细化+商品价)。
 */
@RestController
@RequestMapping("/api/supplier")
public class SupplierController {

    public record SaveRequest(String name, String fullName, String contact, String phone,
                              String address, String creditCode, String extNo, String remark) {
    }

    public record StatusRequest(Integer status) {
    }

    public record UpsertRequest(String extNo, String name, String fullName, String contact,
                                String phone, String address, String creditCode, String remark) {
    }

    public record BindBrandRequest(Long brandId, Boolean isPrimary) {
    }

    public record BrandTermRequest(Integer settleType, Long settleValue, Long penalty,
                                   Integer warrantyMonths, String serviceFeeJson) {
    }

    public record CategoryTermRequest(Long categoryId, Integer settleType, Long settleValue) {
    }

    public record GoodsTermRequest(Long goodsId, Long price, Long balance) {
    }

    private final SupplierService supplierService;
    private final SupplierBrandService brandService;
    private final SupplierTermService termService;

    public SupplierController(SupplierService supplierService, SupplierBrandService brandService,
            SupplierTermService termService) {
        this.supplierService = supplierService;
        this.brandService = brandService;
        this.termService = termService;
    }

    // ---- 厂商本体 ----

    @PostMapping("/suppliers")
    public ApiResponse<Long> create(@RequestBody SaveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(supplierService.create(orgId, toCmd(body), userId));
    }

    /** 外部同步通道:ext_no 幂等 upsert(SRM/金蝶类;认证走 RBAC,同菜单闸)。 */
    @PostMapping("/upsert")
    public ApiResponse<Map<String, Object>> upsert(@RequestBody UpsertRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(supplierService.upsertByExtNo(orgId,
                new SupplierService.SaveCmd(body.name(), body.fullName(), body.contact(), body.phone(),
                        body.address(), body.creditCode(), body.extNo(), body.remark())));
    }

    @GetMapping("/suppliers")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(supplierService.page(orgId, keyword, status, page, size));
    }

    @GetMapping("/suppliers/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(supplierService.get(orgId, id));
    }

    @PutMapping("/suppliers/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody SaveRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        supplierService.update(orgId, id, toCmd(body));
        return ApiResponse.ok();
    }

    @PostMapping("/suppliers/{id}/status")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        supplierService.changeStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }

    // ---- 品牌关联 ----

    @GetMapping("/suppliers/{id}/brands")
    public ApiResponse<List<Map<String, Object>>> brands(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(brandService.brands(orgId, id));
    }

    @PostMapping("/suppliers/{id}/brands")
    public ApiResponse<Void> bind(@PathVariable long id, @RequestBody BindBrandRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        brandService.bind(orgId, id, body.brandId(), Boolean.TRUE.equals(body.isPrimary()));
        return ApiResponse.ok();
    }

    @PostMapping("/suppliers/{id}/brands/{brandId}/unbind")
    public ApiResponse<Void> unbind(@PathVariable long id, @PathVariable long brandId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        brandService.unbind(orgId, id, brandId);
        return ApiResponse.ok();
    }

    @PostMapping("/suppliers/{id}/brands/{brandId}/primary")
    public ApiResponse<Void> setPrimary(@PathVariable long id, @PathVariable long brandId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        brandService.setPrimary(orgId, id, brandId);
        return ApiResponse.ok();
    }

    // ---- 三层结算条款 ----

    @GetMapping("/suppliers/{id}/terms")
    public ApiResponse<List<Map<String, Object>>> terms(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(termService.terms(orgId, id));
    }

    @PutMapping("/suppliers/{id}/terms/brands/{brandId}")
    public ApiResponse<Void> saveBrandTerm(@PathVariable long id, @PathVariable long brandId,
            @RequestBody BrandTermRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        termService.saveBrandTerm(orgId, id, brandId,
                new SupplierTermService.BrandTermCmd(body.settleType(), body.settleValue(),
                        body.penalty(), body.warrantyMonths(), body.serviceFeeJson()));
        return ApiResponse.ok();
    }

    @PostMapping("/suppliers/{id}/terms/brands/{brandId}/categories")
    public ApiResponse<Void> saveCategoryTerm(@PathVariable long id, @PathVariable long brandId,
            @RequestBody CategoryTermRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        termService.saveCategoryTerm(orgId, id, brandId, body.categoryId(), body.settleType(),
                body.settleValue());
        return ApiResponse.ok();
    }

    @DeleteMapping("/suppliers/{id}/terms/brands/{brandId}/categories/{categoryId}")
    public ApiResponse<Void> removeCategoryTerm(@PathVariable long id, @PathVariable long brandId,
            @PathVariable long categoryId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        termService.removeCategoryTerm(orgId, id, brandId, categoryId);
        return ApiResponse.ok();
    }

    @PostMapping("/suppliers/{id}/terms/brands/{brandId}/goods")
    public ApiResponse<Void> saveGoodsTerm(@PathVariable long id, @PathVariable long brandId,
            @RequestBody GoodsTermRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        termService.saveGoodsTerm(orgId, id, brandId, body.goodsId(), body.price(), body.balance());
        return ApiResponse.ok();
    }

    @DeleteMapping("/suppliers/{id}/terms/brands/{brandId}/goods/{goodsId}")
    public ApiResponse<Void> removeGoodsTerm(@PathVariable long id, @PathVariable long brandId,
            @PathVariable long goodsId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        termService.removeGoodsTerm(orgId, id, brandId, goodsId);
        return ApiResponse.ok();
    }

    private SupplierService.SaveCmd toCmd(SaveRequest body) {
        return new SupplierService.SaveCmd(body.name(), body.fullName(), body.contact(), body.phone(),
                body.address(), body.creditCode(), body.extNo(), body.remark());
    }
}
