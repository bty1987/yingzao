package com.yingzao.addon.customerprofile;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户收款账户端点(账号脱敏展示,明文不出档案域)。
 */
@RestController
@RequestMapping("/api/customerprofile")
public class AccountController {

    public record AddAccountRequest(Integer type, String holderName, String bankName, String accountNo) {
    }

    public record UpdateAccountStatusRequest(Integer status) {
    }

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/customers/{id}/accounts")
    public ApiResponse<List<java.util.Map<String, Object>>> list(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accountService.list(orgId, id));
    }

    @PostMapping("/customers/{id}/accounts")
    public ApiResponse<Long> add(@PathVariable long id, @RequestBody AddAccountRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accountService.add(orgId, id,
                new AccountService.AddAccountCmd(body.type(), body.holderName(), body.bankName(), body.accountNo())));
    }

    @PostMapping("/customers/{id}/accounts/{accountId}/default")
    public ApiResponse<Void> setDefault(@PathVariable long id, @PathVariable long accountId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accountService.setDefault(orgId, id, accountId);
        return ApiResponse.ok();
    }

    @PostMapping("/customers/{id}/accounts/{accountId}/status")
    public ApiResponse<Void> setStatus(@PathVariable long id, @PathVariable long accountId,
            @RequestBody UpdateAccountStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accountService.setStatus(orgId, id, accountId, body.status());
        return ApiResponse.ok();
    }
}
