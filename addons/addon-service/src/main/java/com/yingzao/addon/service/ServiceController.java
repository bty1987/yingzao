package com.yingzao.addon.service;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

/**
 * 售后服务端点(归 /api/service prefix 菜单闸,零新增 RBAC 类型):
 * 投诉(登记/开始处理/标记已处理)/回访(手工登记/草稿/完成/无法完成)/
 * 保修(登记/作废/分页 phase 徽章)/组织配置。
 */
@RestController
@RequestMapping("/api/service")
public class ServiceController {

    public record ComplaintRequest(Long orderId, String type, String level, String content,
            String promiseTime) {
    }

    public record HandleRequest(String handleResult) {
    }

    public record VisitRequest(Long orderId, String planTime) {
    }

    public record DraftRequest(String draftContent) {
    }

    public record FinishRequest(Integer rating, String content) {
    }

    public record FailRequest(String reason) {
    }

    public record GuaranteeRequest(Long orderId, String startTime, Integer months, String remark) {
    }

    public record ConfigRequest(Integer visitOnComplaint, Integer visitOnAcceptance,
            Integer acceptanceVisitDays, Integer warrantyMonths) {
    }

    private final ComplaintService complaints;
    private final VisitService visits;
    private final GuaranteeService guarantees;
    private final ServiceSupport support;

    public ServiceController(ComplaintService complaints, VisitService visits,
            GuaranteeService guarantees, ServiceSupport support) {
        this.complaints = complaints;
        this.visits = visits;
        this.guarantees = guarantees;
        this.support = support;
    }

    private static long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private static long userId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("yz.userId");
        return userId == null ? 0 : userId;
    }

    // ---- 投诉 ----

    @GetMapping("/complaints")
    public ApiResponse<Map<String, Object>> pageComplaints(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(complaints.page(orgId(request), status, orderNo, page, size));
    }

    @PostMapping("/complaints")
    public ApiResponse<Long> createComplaint(@RequestBody ComplaintRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(complaints.create(orgId(request), body == null ? null
                : new ComplaintService.CreateCmd(body.orderId(), body.type(), body.level(),
                        body.content(), body.promiseTime()),
                userId(request)));
    }

    @PostMapping("/complaints/{id}/start")
    public ApiResponse<Void> startComplaint(@PathVariable long id, HttpServletRequest request) {
        complaints.start(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/complaints/{id}/handle")
    public ApiResponse<Void> handleComplaint(@PathVariable long id, @RequestBody HandleRequest body,
            HttpServletRequest request) {
        complaints.handle(orgId(request), id, body == null ? null : body.handleResult(),
                userId(request));
        return ApiResponse.ok();
    }

    // ---- 回访 ----

    @GetMapping("/visits")
    public ApiResponse<Map<String, Object>> pageVisits(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(visits.page(orgId(request), status, bizType, orderNo, page, size));
    }

    @PostMapping("/visits")
    public ApiResponse<Long> createVisit(@RequestBody VisitRequest body, HttpServletRequest request) {
        return ApiResponse.ok(visits.create(orgId(request), body == null ? null
                : new VisitService.CreateCmd(body.orderId(), body.planTime()), userId(request)));
    }

    @PostMapping("/visits/{id}/draft")
    public ApiResponse<Void> saveDraft(@PathVariable long id, @RequestBody DraftRequest body,
            HttpServletRequest request) {
        visits.saveDraft(orgId(request), id, body == null ? null : body.draftContent(),
                userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/visits/{id}/finish")
    public ApiResponse<Void> finishVisit(@PathVariable long id, @RequestBody FinishRequest body,
            HttpServletRequest request) {
        visits.finish(orgId(request), id, body == null ? null : body.rating(),
                body == null ? null : body.content(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/visits/{id}/fail")
    public ApiResponse<Void> failVisit(@PathVariable long id, @RequestBody FailRequest body,
            HttpServletRequest request) {
        visits.fail(orgId(request), id, body == null ? null : body.reason(), userId(request));
        return ApiResponse.ok();
    }

    // ---- 保修 ----

    @GetMapping("/guarantees")
    public ApiResponse<Map<String, Object>> pageGuarantees(@RequestParam(required = false) Integer phase,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(guarantees.page(orgId(request), phase, orderNo, page, size));
    }

    @PostMapping("/guarantees")
    public ApiResponse<Long> createGuarantee(@RequestBody GuaranteeRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(guarantees.create(orgId(request), body == null ? null
                : new GuaranteeService.CreateCmd(body.orderId(), body.startTime(), body.months(),
                        body.remark()),
                userId(request)));
    }

    @PostMapping("/guarantees/{id}/cancel")
    public ApiResponse<Void> cancelGuarantee(@PathVariable long id, HttpServletRequest request) {
        guarantees.cancel(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    // ---- 组织配置 ----

    @GetMapping("/config")
    public ApiResponse<Object> getConfig(HttpServletRequest request) {
        return ApiResponse.ok(support.config(orgId(request)));
    }

    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(@RequestBody ConfigRequest body, HttpServletRequest request) {
        support.updateConfig(orgId(request), body == null ? null
                : new ServiceSupport.ConfigUpdate(body.visitOnComplaint(), body.visitOnAcceptance(),
                        body.acceptanceVisitDays(), body.warrantyMonths()));
        return ApiResponse.ok();
    }
}
