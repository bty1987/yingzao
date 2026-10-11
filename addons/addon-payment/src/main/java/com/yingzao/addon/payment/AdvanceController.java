package com.yingzao.addon.payment;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 预付款端点:科目字典 / 账户台账 / 收入·划扣·提现(原子扣减,余额不足拒绝)/ 流水。
 * move 请求统一携带业务溯源三元组(划扣必带,收入/提现可空)。
 */
@RestController
@RequestMapping("/api/payment/advance")
public class AdvanceController {

    public record CreateSubjectRequest(String code, String name) {
    }

    public record UpdateSubjectStatusRequest(Integer status) {
    }

    public record MoveRequest(String subjectCode, String refType, String refId, Long amount,
            String bizAddon, String bizType, String bizId, String remark) {
    }

    private final AdvanceService advance;

    public AdvanceController(AdvanceService advance) {
        this.advance = advance;
    }

    @GetMapping("/subjects")
    public ApiResponse<List<Map<String, Object>>> subjects(
            @RequestParam(defaultValue = "true") boolean includeDisabled, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(advance.subjects(orgId, includeDisabled));
    }

    @PostMapping("/subjects")
    public ApiResponse<Long> createSubject(@RequestBody CreateSubjectRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(advance.createSubject(orgId, body.code(), body.name()));
    }

    @PostMapping("/subjects/{id}/status")
    public ApiResponse<Void> updateSubjectStatus(@PathVariable long id,
            @RequestBody UpdateSubjectStatusRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        advance.updateSubjectStatus(orgId, id, body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }

    @GetMapping("/accounts")
    public ApiResponse<List<Map<String, Object>>> accounts(@RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String refType, @RequestParam(required = false) String refId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(advance.accounts(orgId, subjectId, refType, refId));
    }

    @GetMapping("/accounts/{id}/flows")
    public ApiResponse<Map<String, Object>> flows(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(advance.flows(orgId, id, page, size));
    }

    @PostMapping("/charge")
    public ApiResponse<Long> charge(@RequestBody MoveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(advance.move(orgId, body.subjectCode(), body.refType(), body.refId(),
                amount(body), AdvanceService.FLOW_CHARGE, body.bizAddon(), body.bizType(), body.bizId(),
                userId, body.remark()));
    }

    @PostMapping("/deduct")
    public ApiResponse<Long> deduct(@RequestBody MoveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(advance.move(orgId, body.subjectCode(), body.refType(), body.refId(),
                amount(body), AdvanceService.FLOW_DEDUCT, body.bizAddon(), body.bizType(), body.bizId(),
                userId, body.remark()));
    }

    @PostMapping("/withdraw")
    public ApiResponse<Long> withdraw(@RequestBody MoveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(advance.move(orgId, body.subjectCode(), body.refType(), body.refId(),
                amount(body), AdvanceService.FLOW_WITHDRAW, body.bizAddon(), body.bizType(), body.bizId(),
                userId, body.remark()));
    }

    private long amount(MoveRequest body) {
        return body.amount() == null ? 0 : body.amount();
    }
}
