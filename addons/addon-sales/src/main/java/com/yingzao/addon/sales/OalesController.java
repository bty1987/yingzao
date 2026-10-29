package com.yingzao.addon.sales;

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
 * 外销子域端点(D6a;归 /api/sales/oales prefix 菜单闸,addon.yml 声明)。
 */
@RestController
@RequestMapping("/api/sales/oales")
public class OalesController {

    public record ReasonRequest(String reason) {
    }

    private final OalesService oales;

    public OalesController(OalesService oales) {
        this.oales = oales;
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(oales.page(orgId(request), orderId, brandId, type, status, page, size));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody OalesService.OalesCmd cmd, HttpServletRequest request) {
        return ApiResponse.ok(oales.create(orgId(request), cmd, userId(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(oales.detail(orgId(request), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody OalesService.OalesCmd cmd,
            HttpServletRequest request) {
        oales.update(orgId(request), id, cmd, userId(request));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDraft(@PathVariable long id, HttpServletRequest request) {
        oales.deleteDraft(orgId(request), id, userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Void> submit(@PathVariable long id, HttpServletRequest request) {
        oales.submit(orgId(request), id, userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        oales.cancel(orgId(request), id, userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        oales.confirm(orgId(request), id, userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable long id, @RequestBody ReasonRequest req,
            HttpServletRequest request) {
        oales.reject(orgId(request), id, req == null ? null : req.reason(), userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        oales.finish(orgId(request), id, userId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/abate")
    public ApiResponse<Void> abate(@PathVariable long id, @RequestBody ReasonRequest req,
            HttpServletRequest request) {
        oales.abate(orgId(request), id, req == null ? null : req.reason(), userId(request));
        return ApiResponse.ok(null);
    }
}
