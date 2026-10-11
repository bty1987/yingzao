package com.yingzao.core.approval;

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
 * 轻量审批接口(内核):/api/approvals。
 * 业务插件经 ApprovalService.submit 提交审批单(biz_* 溯源透传),
 * 办结后插件监听 ApprovalFinishedEvent 回写自身业务状态。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    public record SubmitRequest(String title, String bizAddon, String bizType, String bizId, Long assigneeId) {
    }

    public record ActRequest(String comment) {
    }

    public record TransferRequest(Long toUserId, String comment) {
    }

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping
    public ApiResponse<Long> submit(@RequestBody SubmitRequest body, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        long id = approvalService.submit(new ApprovalService.SubmitCmd(orgId, userId, body.title(),
                body.bizAddon(), body.bizType(), body.bizId(), body.assigneeId()));
        return ApiResponse.ok(id);
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request,
            @RequestParam(defaultValue = "todo") String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(approvalService.list(userId, role, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(approvalService.detail(userId, id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable long id, @RequestBody(required = false) ActRequest body,
            HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        approvalService.approve(orgId, userId, id, body == null ? null : body.comment());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable long id, @RequestBody(required = false) ActRequest body,
            HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        approvalService.reject(orgId, userId, id, body == null ? null : body.comment());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<Void> transfer(@PathVariable long id, @RequestBody TransferRequest body,
            HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        approvalService.transfer(orgId, userId, id, body.toUserId(), body.comment());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        approvalService.cancel(orgId, userId, id);
        return ApiResponse.ok();
    }
}
