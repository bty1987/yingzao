package com.yingzao.addon.basedata;

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
 * 主材型号库端点(独立价目模型,goods 关联可空;点位计算口径随 addon-sales)。
 */
@RestController
@RequestMapping("/api/basedata")
public class MaterialModelController {

    public record CreateModelRequest(String name, Long goodsId, Long brandId, String unit,
            Long price, Long purchase, Integer discount, Integer calcType,
            Long baseValue, Integer pointValue) {
    }

    public record UpdateModelRequest(String name, Long goodsId, Long brandId, String unit,
            Long price, Long purchase, Integer discount, Integer calcType,
            Long baseValue, Integer pointValue) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final MaterialModelService modelService;

    public MaterialModelController(MaterialModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long brandId, @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(modelService.page(orgId, keyword, brandId, status, page, size));
    }

    @PostMapping("/models")
    public ApiResponse<Long> create(@RequestBody CreateModelRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(modelService.create(orgId, new MaterialModelService.CreateModelCmd(
                body.name(), body.goodsId(), body.brandId(), body.unit(), body.price(), body.purchase(),
                body.discount(), body.calcType(), body.baseValue(), body.pointValue()), userId));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateModelRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        modelService.update(orgId, id, body.name(), body.goodsId(), body.brandId(), body.unit(),
                body.price(), body.purchase(), body.discount(), body.calcType(), body.baseValue(),
                body.pointValue());
        return ApiResponse.ok();
    }

    @PostMapping("/models/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        modelService.updateStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }
}
