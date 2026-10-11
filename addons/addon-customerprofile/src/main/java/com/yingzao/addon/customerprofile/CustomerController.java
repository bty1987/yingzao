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
 * 客户档案端点:建档 / get-or-create / 查询 / 更新 / 多电话(显式默认+解密留痕)/ 审计。
 */
@RestController
@RequestMapping("/api/customerprofile")
public class CustomerController {

    public record CreateCustomerRequest(String name, String phone, String gender, String address, String identity) {
    }

    public record ExistCustomerRequest(String phone, String name) {
    }

    public record UpdateCustomerRequest(String name, String gender, String address, String identity) {
    }

    public record AddPhoneRequest(String phone) {
    }

    public record UpdatePhoneStatusRequest(Integer status) {
    }

    public record RevealPhoneRequest(String purpose) {
    }

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/customers")
    public ApiResponse<Long> create(@RequestBody CreateCustomerRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(customerService.create(orgId,
                new CustomerService.CreateCustomerCmd(body.name(), body.phone(), body.gender(),
                        body.address(), body.identity()),
                userId));
    }

    @PostMapping("/customers/exist")
    public ApiResponse<Map<String, Object>> exist(@RequestBody ExistCustomerRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(customerService.exist(orgId, body.phone(), body.name(), userId));
    }

    @GetMapping("/customers")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(customerService.page(orgId, keyword, page, size));
    }

    @GetMapping("/customers/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(customerService.get(orgId, id));
    }

    @PutMapping("/customers/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateCustomerRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        customerService.update(orgId, id, body.name(), body.gender(), body.address(), body.identity());
        return ApiResponse.ok();
    }

    @PostMapping("/customers/{id}/phones")
    public ApiResponse<Long> addPhone(@PathVariable long id, @RequestBody AddPhoneRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(customerService.addPhone(orgId, id, body.phone()));
    }

    @GetMapping("/customers/{id}/phones")
    public ApiResponse<List<Map<String, Object>>> phones(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(customerService.phones(orgId, id));
    }

    @PostMapping("/customers/{id}/phones/{phoneId}/default")
    public ApiResponse<Void> setDefaultPhone(@PathVariable long id, @PathVariable long phoneId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        customerService.setDefaultPhone(orgId, id, phoneId);
        return ApiResponse.ok();
    }

    @PostMapping("/customers/{id}/phones/{phoneId}/status")
    public ApiResponse<Void> setPhoneStatus(@PathVariable long id, @PathVariable long phoneId,
            @RequestBody UpdatePhoneStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        customerService.setPhoneStatus(orgId, id, phoneId, body.status());
        return ApiResponse.ok();
    }

    @PostMapping("/customers/{id}/phones/{phoneId}/reveal")
    public ApiResponse<Map<String, Object>> revealPhone(@PathVariable long id, @PathVariable long phoneId,
            @RequestBody RevealPhoneRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(customerService.revealPhone(orgId, id, phoneId, userId, body.purpose()));
    }

    @GetMapping("/customers/{id}/phone-access-logs")
    public ApiResponse<Map<String, Object>> accessLogs(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(customerService.accessLogs(orgId, id, page, size));
    }
}
