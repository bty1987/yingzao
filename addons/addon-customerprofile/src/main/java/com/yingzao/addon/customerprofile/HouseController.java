package com.yingzao.addon.customerprofile;

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
 * 房屋档案端点:建档 / get-or-create / 详情(解密) / 更新(白名单字段) / 分页。
 */
@RestController
@RequestMapping("/api/customerprofile")
public class HouseController {

    public record CreateHouseRequest(Long villageId, String number, HouseService.HouseFields fields) {
    }

    public record ExistHouseRequest(Long villageId, String number, HouseService.HouseFields fields) {
    }

    public record UpdateHouseRequest(HouseService.HouseFields fields) {
    }

    private final HouseService houseService;

    public HouseController(HouseService houseService) {
        this.houseService = houseService;
    }

    @PostMapping("/houses")
    public ApiResponse<Long> create(@RequestBody CreateHouseRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(houseService.create(orgId, body.villageId(), body.number(), body.fields(), userId));
    }

    @PostMapping("/houses/exist")
    public ApiResponse<Map<String, Object>> exist(@RequestBody ExistHouseRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(houseService.exist(orgId, body.villageId(), body.number(), body.fields(), userId));
    }

    @GetMapping("/houses/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(houseService.get(orgId, id));
    }

    @PutMapping("/houses/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateHouseRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        houseService.update(orgId, id, body.fields());
        return ApiResponse.ok();
    }

    @GetMapping("/houses")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long villageId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(houseService.page(orgId, villageId, keyword, page, size));
    }
}
