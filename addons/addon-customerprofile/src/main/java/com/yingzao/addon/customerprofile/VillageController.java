package com.yingzao.addon.customerprofile;

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
 * 楼盘档案端点:城市/城区字典 + 楼盘(树)维护与检索。
 */
@RestController
@RequestMapping("/api/customerprofile")
public class VillageController {

    public record CreateCityRequest(String name) {
    }

    public record CreateProperRequest(String name) {
    }

    public record CreateVillageRequest(String name, Long cityId, Long properId, Long parentId,
                                       String developer, String deliveryTime,
                                       Integer x, Integer y, Integer mileage, String content) {
    }

    public record UpdateVillageRequest(String name, Long cityId, Long properId, Long parentId,
                                       String developer, String deliveryTime,
                                       Integer x, Integer y, Integer mileage, String content) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final VillageService villageService;

    public VillageController(VillageService villageService) {
        this.villageService = villageService;
    }

    @GetMapping("/cities")
    public ApiResponse<List<Map<String, Object>>> cities() {
        return ApiResponse.ok(villageService.cities());
    }

    @PostMapping("/cities")
    public ApiResponse<Long> addCity(@RequestBody CreateCityRequest body) {
        return ApiResponse.ok(villageService.addCity(body.name()));
    }

    @PostMapping("/cities/{cityId}/propers")
    public ApiResponse<Long> addProper(@PathVariable long cityId, @RequestBody CreateProperRequest body) {
        return ApiResponse.ok(villageService.addProper(cityId, body.name()));
    }

    @GetMapping("/villages")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long properId, @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(villageService.page(orgId, keyword, properId, status, page, size));
    }

    @GetMapping("/villages/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(villageService.get(orgId, id));
    }

    @PostMapping("/villages")
    public ApiResponse<Long> create(@RequestBody CreateVillageRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(villageService.create(orgId, body.name(), body.cityId(), body.properId(),
                body.parentId(), body.developer(), body.deliveryTime(), body.x(), body.y(),
                body.mileage(), body.content()));
    }

    @PutMapping("/villages/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateVillageRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        villageService.update(orgId, id, body.name(), body.properId(), body.cityId(), body.parentId(),
                body.developer(), body.deliveryTime(), body.x(), body.y(), body.mileage(), body.content());
        return ApiResponse.ok();
    }

    @PostMapping("/villages/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        villageService.updateStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }
}
