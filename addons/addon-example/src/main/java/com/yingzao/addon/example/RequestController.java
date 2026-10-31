package com.yingzao.addon.example;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 示范申请单端点(schema 驱动表单审批):POST 发起(表单值+处理人)→ 返回业务单 id;
 * 移动端提交成功后持 approval-forms 段 submit 契约跳审批详情。鉴权=authenticated(发起是全员能力)。
 */
@RestController
@RequestMapping("/api/example/requests")
public class RequestController {

    public record CreateRequest(Long orderId, String orderLabel, String village, String customer,
                                String reason, Long amount, List<Long> images, Long assigneeId) {
    }

    private final RequestService requests;

    public RequestController(RequestService requests) {
        this.requests = requests;
    }

    /** 返回 {requestId, approvalId}:approvalId 是移动端发起后直跳审批详情的统一契约。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        long approvalId = requests.create(orgId, userId, new RequestService.CreateCmd(body.orderId(),
                body.orderLabel(), body.village(), body.customer(), body.reason(), body.amount(),
                body.images(), body.assigneeId()));
        return ApiResponse.ok(Map.of("approvalId", approvalId));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> mine(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(requests.listMine(userId));
    }
}
