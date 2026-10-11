package com.yingzao.addon.market;

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
 * 投放物端点:活动(状态机含恢复)/渠道(启停含恢复)/投放源(码+UTM 三件套)。金额入参厘。
 */
@RestController
@RequestMapping("/api/market")
public class CampaignController {

    public record CampaignRequest(String name, String description, Long budget) {
    }

    public record ChannelRequest(String name) {
    }

    public record SourceRequest(Long campaignId, Long channelId, String name, String url,
            String utmSource, String utmMedium, String utmCampaign) {
    }

    private final CampaignService campaigns;
    private final SourceService sources;

    public CampaignController(CampaignService campaigns, SourceService sources) {
        this.campaigns = campaigns;
        this.sources = sources;
    }

    // ---- 活动 ----

    @PostMapping("/campaigns")
    public ApiResponse<Map<String, Object>> createCampaign(@RequestBody CampaignRequest body,
            HttpServletRequest request) {
        long id = campaigns.createCampaign(orgId(request), body.name(), body.description(),
                body.budget() == null ? 0 : body.budget());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/campaigns/{id}")
    public ApiResponse<Void> updateCampaign(@PathVariable long id, @RequestBody CampaignRequest body,
            HttpServletRequest request) {
        campaigns.updateCampaign(orgId(request), id, body.name(), body.description(), body.budget());
        return ApiResponse.ok();
    }

    @PostMapping("/campaigns/{id}/publish")
    public ApiResponse<Void> publishCampaign(@PathVariable long id, HttpServletRequest request) {
        campaigns.publishCampaign(orgId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/campaigns/{id}/disable")
    public ApiResponse<Void> disableCampaign(@PathVariable long id, HttpServletRequest request) {
        campaigns.disableCampaign(orgId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/campaigns/{id}/archive")
    public ApiResponse<Void> archiveCampaign(@PathVariable long id, HttpServletRequest request) {
        campaigns.archiveCampaign(orgId(request), id);
        return ApiResponse.ok();
    }

    @GetMapping("/campaigns")
    public ApiResponse<List<Map<String, Object>>> listCampaigns(
            @RequestParam(required = false) Integer status, @RequestParam(required = false) String name,
            HttpServletRequest request) {
        return ApiResponse.ok(campaigns.listCampaigns(orgId(request), status, name));
    }

    // ---- 渠道 ----

    @PostMapping("/channels")
    public ApiResponse<Map<String, Object>> createChannel(@RequestBody ChannelRequest body,
            HttpServletRequest request) {
        long id = campaigns.createChannel(orgId(request), body.name());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<Void> updateChannel(@PathVariable long id, @RequestBody ChannelRequest body,
            HttpServletRequest request) {
        campaigns.updateChannel(orgId(request), id, body.name());
        return ApiResponse.ok();
    }

    @PostMapping("/channels/{id}/disable")
    public ApiResponse<Void> disableChannel(@PathVariable long id, HttpServletRequest request) {
        campaigns.disableChannel(orgId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/channels/{id}/enable")
    public ApiResponse<Void> enableChannel(@PathVariable long id, HttpServletRequest request) {
        campaigns.enableChannel(orgId(request), id);
        return ApiResponse.ok();
    }

    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> listChannels(
            @RequestParam(required = false) Integer status, HttpServletRequest request) {
        return ApiResponse.ok(campaigns.listChannels(orgId(request), status));
    }

    // ---- 投放源 ----

    @PostMapping("/sources")
    public ApiResponse<Map<String, Object>> createSource(@RequestBody SourceRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(sources.create(orgId(request), body.campaignId(), body.channelId(),
                body.name(), body.url(), body.utmSource(), body.utmMedium(), body.utmCampaign()));
    }

    @GetMapping("/sources")
    public ApiResponse<List<Map<String, Object>>> listSources(
            @RequestParam(required = false) Long campaignId, @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Integer status, HttpServletRequest request) {
        return ApiResponse.ok(sources.list(orgId(request), campaignId, channelId, status));
    }

    @GetMapping("/sources/{id}")
    public ApiResponse<Map<String, Object>> sourceDetail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(sources.detail(orgId(request), id));
    }

    @PostMapping("/sources/{id}/disable")
    public ApiResponse<Void> disableSource(@PathVariable long id, HttpServletRequest request) {
        sources.disable(orgId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/sources/{id}/enable")
    public ApiResponse<Void> enableSource(@PathVariable long id, HttpServletRequest request) {
        sources.enable(orgId(request), id);
        return ApiResponse.ok();
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }
}
