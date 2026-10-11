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
 * 整装定材包端点:自建主数据行 / 引用主数据建引用行(快照,仅浮动量可改)。
 */
@RestController
@RequestMapping("/api/basedata")
public class MaterialPackageController {

    public record CreatePackageRequest(String name, Long selling, Long balance, Long surplus, Integer calc) {
    }

    public record CreateReferenceRequest(Long surplus) {
    }

    public record UpdatePackageRequest(String name, Long selling, Long balance, Long surplus, Integer calc) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final MaterialPackageService packageService;

    public MaterialPackageController(MaterialPackageService packageService) {
        this.packageService = packageService;
    }

    @GetMapping("/packages")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean onlyMaster,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(packageService.page(orgId, keyword, status, onlyMaster, page, size));
    }

    @PostMapping("/packages")
    public ApiResponse<Long> create(@RequestBody CreatePackageRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(packageService.create(orgId, body.name(), body.selling(), body.balance(),
                body.surplus(), body.calc(), userId));
    }

    @PostMapping("/packages/{id}/reference")
    public ApiResponse<Long> createReference(@PathVariable long id, @RequestBody CreateReferenceRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(packageService.createReference(orgId, id, body.surplus(), userId));
    }

    @PutMapping("/packages/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdatePackageRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        packageService.update(orgId, id, body.name(), body.selling(), body.balance(), body.surplus(),
                body.calc());
        return ApiResponse.ok();
    }

    @PostMapping("/packages/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        packageService.updateStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }
}
