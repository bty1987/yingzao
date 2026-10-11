package com.yingzao.addon.payment;

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

/** 款项类型字典端点(组织级;code 协议键建后不可改)。 */
@RestController
@RequestMapping("/api/payment")
public class BillTypeController {

    public record CreateTypeRequest(String code, String name, Integer sort, String scaleTemplate) {
    }

    public record UpdateTypeRequest(String name, Integer sort, String scaleTemplate) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final BillTypeService types;

    public BillTypeController(BillTypeService types) {
        this.types = types;
    }

    @GetMapping("/bill-types")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "true") boolean includeDisabled, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(types.list(orgId, includeDisabled));
    }

    @PostMapping("/bill-types")
    public ApiResponse<Long> create(@RequestBody CreateTypeRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(types.create(orgId, body.code(), body.name(), body.sort(), body.scaleTemplate()));
    }

    @PutMapping("/bill-types/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateTypeRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        types.update(orgId, id, body.name(), body.sort(), body.scaleTemplate());
        return ApiResponse.ok();
    }

    @PostMapping("/bill-types/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        types.updateStatus(orgId, id, body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }
}
