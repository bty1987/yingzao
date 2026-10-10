package com.yingzao.addon.customer;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客源机会接口(V2):分配挂在客源资源下,机会列表与状态流转独立成资源。
 */
@RestController
@RequestMapping("/api/customer")
public class ChanceController {

    public record AssignRequest(String mode, Long assigneeId) {
    }

    private final ChanceService chanceService;

    public ChanceController(ChanceService chanceService) {
        this.chanceService = chanceService;
    }

    @PostMapping("/leads/{id}/assign")
    public ApiResponse<Long> assign(@PathVariable long id, @RequestBody AssignRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(chanceService.assign(orgId, id, body.mode(), body.assigneeId()));
    }

    @GetMapping("/chances")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(chanceService.list(orgId));
    }

    @PostMapping("/chances/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        chanceService.confirm(orgId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/chances/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        chanceService.complete(orgId, id);
        return ApiResponse.ok();
    }
}
