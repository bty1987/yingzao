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

@RestController
@RequestMapping("/api/customer")
public class LeadController {

    public record CreateLeadRequest(String name, String phone, String source, String note) {
    }

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @PostMapping("/leads")
    public ApiResponse<Long> create(@RequestBody CreateLeadRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(leadService.create(orgId, body.name(), body.phone(), body.source(), body.note()));
    }

    @GetMapping("/leads")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(leadService.list(orgId));
    }

    @PostMapping("/leads/{id}/convert")
    public ApiResponse<Void> convert(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        leadService.convert(orgId, id);
        return ApiResponse.ok();
    }
}
