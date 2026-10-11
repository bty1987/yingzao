package com.yingzao.addon.basedata;

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
 * 商品档案端点:主档(no/barcode/name 三路组织内唯一)+ SKU 属性 + 多价格体系 + 套餐成员。
 */
@RestController
@RequestMapping("/api/basedata")
public class GoodsController {

    public record CreateGoodsRequest(String name, String alias, String pinyin, String barcode,
            Long categoryId, Long brandId, String unit, Integer type,
            Long purchase, Long selling, Long confine, Integer unitRate) {
    }

    public record UpdateGoodsRequest(String name, String alias, String pinyin, String barcode,
            Long categoryId, Long brandId, String unit,
            Long purchase, Long selling, Long confine, Integer unitRate) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    public record AddSkuAttrRequest(Integer attrType, String value) {
    }

    public record UpdateSkuAttrStatusRequest(Integer status) {
    }

    public record PriceRequest(String name, Long purchase, Long selling, Long confine,
            Integer ctype, Long cvalue, String beginTime, String endTime) {
    }

    public record UpdatePriceStatusRequest(Integer status) {
    }

    public record AddPackageMemberRequest(Long memberGoodsId, Integer num) {
    }

    public record UpdateMemberStatusRequest(Integer status) {
    }

    private final GoodsService goodsService;

    public GoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    // ---- 主档 ----

    @GetMapping("/goods")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Integer status, @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.page(orgId, keyword, categoryId, brandId, status, type, page, size));
    }

    @GetMapping("/goods/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.get(orgId, id));
    }

    @PostMapping("/goods")
    public ApiResponse<Long> create(@RequestBody CreateGoodsRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(goodsService.create(orgId, new GoodsService.CreateGoodsCmd(
                body.name(), body.alias(), body.pinyin(), body.barcode(),
                body.categoryId() == null ? 0 : body.categoryId(), body.brandId(), body.unit(),
                body.type() == null ? GoodsService.TYPE_GOODS : body.type(),
                body.purchase(), body.selling(), body.confine(), body.unitRate()), userId));
    }

    @PutMapping("/goods/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateGoodsRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.update(orgId, id, body.name(), body.alias(), body.pinyin(), body.barcode(),
                body.categoryId(), body.brandId(), body.unit(), body.purchase(), body.selling(),
                body.confine(), body.unitRate());
        return ApiResponse.ok();
    }

    @PostMapping("/goods/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.updateStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }

    // ---- SKU 属性 ----

    @GetMapping("/goods/{id}/sku-attrs")
    public ApiResponse<List<Map<String, Object>>> skuAttrs(@PathVariable long id,
            @RequestParam(defaultValue = "false") boolean includeDisabled, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.skuAttrs(orgId, id, includeDisabled));
    }

    @PostMapping("/goods/{id}/sku-attrs")
    public ApiResponse<Long> addSkuAttr(@PathVariable long id, @RequestBody AddSkuAttrRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.addSkuAttr(orgId, id,
                body.attrType() == null ? 0 : body.attrType(), body.value()));
    }

    @PostMapping("/goods/{id}/sku-attrs/{attrId}/status")
    public ApiResponse<Void> updateSkuAttrStatus(@PathVariable long id, @PathVariable long attrId,
            @RequestBody UpdateSkuAttrStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.updateSkuAttrStatus(orgId, id, attrId, body.status());
        return ApiResponse.ok();
    }

    // ---- 多价格体系 ----

    @GetMapping("/goods/{id}/prices")
    public ApiResponse<List<Map<String, Object>>> prices(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.prices(orgId, id));
    }

    @PostMapping("/goods/{id}/prices")
    public ApiResponse<Long> addPrice(@PathVariable long id, @RequestBody PriceRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.addPrice(orgId, id, new GoodsService.PriceCmd(
                body.name(), body.purchase(), body.selling(), body.confine(),
                body.ctype(), body.cvalue(), body.beginTime(), body.endTime())));
    }

    @PutMapping("/goods/{id}/prices/{priceId}")
    public ApiResponse<Void> updatePrice(@PathVariable long id, @PathVariable long priceId,
            @RequestBody PriceRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.updatePrice(orgId, id, priceId, new GoodsService.PriceCmd(
                body.name(), body.purchase(), body.selling(), body.confine(),
                body.ctype(), body.cvalue(), body.beginTime(), body.endTime()));
        return ApiResponse.ok();
    }

    @PostMapping("/goods/{id}/prices/{priceId}/status")
    public ApiResponse<Void> updatePriceStatus(@PathVariable long id, @PathVariable long priceId,
            @RequestBody UpdatePriceStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.updatePriceStatus(orgId, id, priceId, body.status());
        return ApiResponse.ok();
    }

    // ---- 套餐成员 ----

    @GetMapping("/goods/{id}/packages")
    public ApiResponse<List<Map<String, Object>>> packageMembers(@PathVariable long id,
            @RequestParam(defaultValue = "false") boolean includeDisabled, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.packageMembers(orgId, id, includeDisabled));
    }

    @PostMapping("/goods/{id}/packages")
    public ApiResponse<Long> addPackageMember(@PathVariable long id, @RequestBody AddPackageMemberRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(goodsService.addPackageMember(orgId, id, body.memberGoodsId(),
                body.num() == null ? 1 : body.num()));
    }

    @PostMapping("/goods/{id}/packages/{memberId}/status")
    public ApiResponse<Void> updatePackageMemberStatus(@PathVariable long id, @PathVariable long memberId,
            @RequestBody UpdateMemberStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        goodsService.updatePackageMemberStatus(orgId, id, memberId, body.status());
        return ApiResponse.ok();
    }
}
