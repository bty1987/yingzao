package com.yingzao.addon.balance;

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
 * 毛利与管理线端点(八枚,归 /api/balance/margin-lines prefix,addon.yml 菜单闸;
 * docs/prd/addon-balance-margin.md)。权限 = S2 基座:集团守卫 = scope 含根,
 * 分公司守卫 = 目标 ∈ scope 且非根且为根直接子组织。
 */
@RestController
@RequestMapping("/api/balance/margin-lines")
public class MarginLineController {

    private final MarginLineService marginLines;

    public MarginLineController(MarginLineService marginLines) {
        this.marginLines = marginLines;
    }

    /** 集团当前线:17 周期(线值+周期状态)+当前版本 id */
    @GetMapping("/group")
    public ApiResponse<Map<String, Object>> group(@RequestParam Integer year,
            HttpServletRequest request) {
        return ApiResponse.ok(marginLines.groupLine(orgId(request), year));
    }

    /** 发布集团管理线(整年 17 项 + baseVersionId 乐观并发闸) */
    @PostMapping("/group/publish")
    public ApiResponse<Map<String, Object>> publishGroup(
            @RequestBody MarginLineService.GroupPublishRequest req, HttpServletRequest request) {
        return ApiResponse.ok(marginLines.publishGroup(orgId(request), userId(request), req));
    }

    /** 集团发布记录分页(操作人+时间+变更数) */
    @GetMapping("/group/versions")
    public ApiResponse<Map<String, Object>> groupVersions(@RequestParam Integer year,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(marginLines.groupVersions(orgId(request), year, page, size));
    }

    /** 分公司选项+配置进度(NONE/COMPLETED,集团身份专用) */
    @GetMapping("/company-options")
    public ApiResponse<Map<String, Object>> companyOptions(@RequestParam Integer year,
            @RequestParam(required = false) String keyword, HttpServletRequest request) {
        return ApiResponse.ok(marginLines.companyOptions(orgId(request), year, keyword));
    }

    /** 分公司当前线:本公司线 + 集团线对照 */
    @GetMapping("/branch")
    public ApiResponse<Map<String, Object>> branch(@RequestParam(required = false) Long orgId,
            @RequestParam Integer year, HttpServletRequest request) {
        return ApiResponse.ok(marginLines.branchLine(orgId(request), orgId, year));
    }

    /** 发布分公司要求线(orgId + 整年 17 项 + baseVersionId) */
    @PostMapping("/branch/publish")
    public ApiResponse<Map<String, Object>> publishBranch(
            @RequestBody MarginLineService.BranchPublishRequest req, HttpServletRequest request) {
        return ApiResponse.ok(marginLines.publishBranch(orgId(request), userId(request), req));
    }

    /** 分公司发布记录分页 */
    @GetMapping("/branch/versions")
    public ApiResponse<Map<String, Object>> branchVersions(@RequestParam(required = false) Long orgId,
            @RequestParam Integer year, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ApiResponse.ok(marginLines.branchVersions(orgId(request), orgId, year, page, size));
    }

    /** 版本修改详情(变更明细:周期×口径 before/after/change) */
    @GetMapping("/versions/{id}")
    public ApiResponse<Map<String, Object>> versionDetail(@PathVariable long id,
            HttpServletRequest request) {
        return ApiResponse.ok(marginLines.versionDetail(orgId(request), id));
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }
}
