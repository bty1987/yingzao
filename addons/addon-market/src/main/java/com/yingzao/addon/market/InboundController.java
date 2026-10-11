package com.yingzao.addon.market;

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
 * 进线端点:登记(内部端点,模拟落地页提交/网销手工补录;开放匿名 API 归 RBAC v2 后排期)/
 * 工作台列表 / 详情(动态字段+留痕+归因+承接结果)/ 重试 / 忽略 / 获客漏斗(按活动/按渠道)。
 */
@RestController
@RequestMapping("/api/market")
public class InboundController {

    public record LeadRequest(Long sourceId, Long campaignId, Long channelId, Long caseId, String name,
            String phone, String acceptMode, String note, Boolean allowAnonymous,
            List<InboundService.FieldItem> items) {
    }

    public record IgnoreRequest(String reason) {
    }

    private final InboundService inbounds;
    private final ShowcaseService showcase;

    public InboundController(InboundService inbounds, ShowcaseService showcase) {
        this.inbounds = inbounds;
        this.showcase = showcase;
    }

    @PostMapping("/inbounds")
    public ApiResponse<Map<String, Object>> create(@RequestBody LeadRequest body, HttpServletRequest request) {
        InboundService.CreateCmd cmd = new InboundService.CreateCmd(body.sourceId(), body.campaignId(),
                body.channelId(), resolveCase(body), null, body.name(), body.phone(), body.acceptMode(),
                body.note(), body.allowAnonymous(), body.items());
        return ApiResponse.ok(inbounds.create(orgId(request), userId(request), cmd));
    }

    @GetMapping("/inbounds")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long campaignId, @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long caseId, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(inbounds.page(orgId(request), status, campaignId, channelId, caseId,
                name, page, size));
    }

    @GetMapping("/inbounds/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(inbounds.detail(orgId(request), id));
    }

    @PostMapping("/inbounds/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable long id, HttpServletRequest request) {
        inbounds.retry(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/inbounds/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable long id, @RequestBody(required = false) IgnoreRequest body,
            HttpServletRequest request) {
        inbounds.ignore(orgId(request), id, userId(request), body == null ? null : body.reason());
        return ApiResponse.ok();
    }

    // ---- 获客漏斗 ----

    @GetMapping("/funnel/campaigns")
    public ApiResponse<List<Map<String, Object>>> funnelByCampaign(HttpServletRequest request) {
        return ApiResponse.ok(inbounds.funnelByCampaign(orgId(request)));
    }

    @GetMapping("/funnel/channels")
    public ApiResponse<List<Map<String, Object>>> funnelByChannels(
            @RequestParam(required = false) Long campaignId, HttpServletRequest request) {
        return ApiResponse.ok(inbounds.funnelByChannel(orgId(request), campaignId));
    }

    // ---- 案例报名(走案例库入口,复用进线链) ----

    @PostMapping("/showcase/cases/{id}/lead")
    public ApiResponse<Map<String, Object>> leadForCase(@PathVariable long id,
            @RequestBody LeadRequest body, HttpServletRequest request) {
        return ApiResponse.ok(showcase.leadForCase(orgId(request), userId(request), id,
                body.name(), body.phone(), body.note(), body.items()));
    }

    @GetMapping("/showcase/leads")
    public ApiResponse<List<Map<String, Object>>> listLeads(
            @RequestParam(required = false) Long caseId,
            @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        return ApiResponse.ok(showcase.listLeads(orgId(request), caseId, limit));
    }

    /** 案例报名时 caseId 由路径携带,登记体中的 caseId 忽略。 */
    private Long resolveCase(LeadRequest body) {
        return null;
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }
}
