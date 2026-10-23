package com.yingzao.addon.target;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 经营目标端点(/api/target;鉴权走内核 RbacInterceptor + addon.yml api 段,菜单闸)。
 */
@RestController
@RequestMapping("/api/target")
public class TargetController {

    public record PlanBody(Long orgNode, String yearMonth, Long amount, String remark, String line) {
    }

    public record AdjustBody(Long amount, String remark) {
    }

    public record StatusBody(Integer status) {
    }

    private final TargetService targets;

    public TargetController(TargetService targets) {
        this.targets = targets;
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long node,
            @RequestParam(required = false) String ym, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(targets.list(orgId, node, ym));
    }

    @PostMapping("/plans")
    public ApiResponse<Long> create(@RequestBody PlanBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(targets.create(orgId, new TargetService.CreateCmd(
                body == null ? null : body.orgNode(), body == null ? null : body.yearMonth(),
                body == null ? null : body.amount(), body == null ? null : body.remark(),
                body == null ? null : body.line()), userId));
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<Void> adjust(@PathVariable long id, @RequestBody AdjustBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        targets.adjust(orgId, id, body == null ? null : body.amount(),
                body == null ? null : body.remark(), userId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/plans/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        targets.delete(orgId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/plans/{id}/status")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        targets.changeStatus(orgId, id, body == null || body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }

    /** 调整留痕(旧值→新值+说明)。 */
    @GetMapping("/plans/{id}/records")
    public ApiResponse<?> records(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(targets.records(orgId, id));
    }

    public record StageBody(String stageKey, Integer sortNo, String beginDay, String endDay,
            Long amount, String remark) {
    }

    /** 阶段列表(含实时实绩/达成率,阶段区间日切片)。 */
    @GetMapping("/plans/{id}/stages")
    public ApiResponse<?> stages(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(targets.stages(orgId, id));
    }

    @PostMapping("/plans/{id}/stages")
    public ApiResponse<Long> createStage(@PathVariable long id, @RequestBody StageBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(targets.createStage(orgId, id, new TargetService.StageCmd(
                body == null ? null : body.stageKey(), body == null ? null : body.sortNo(),
                body == null ? null : body.beginDay(), body == null ? null : body.endDay(),
                body == null ? null : body.amount(), body == null ? null : body.remark()), userId));
    }

    @PutMapping("/stages/{id}")
    public ApiResponse<Void> updateStage(@PathVariable long id, @RequestBody StageBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        targets.updateStage(orgId, id, new TargetService.StageCmd(
                body == null ? null : body.stageKey(), body == null ? null : body.sortNo(),
                body == null ? null : body.beginDay(), body == null ? null : body.endDay(),
                body == null ? null : body.amount(), body == null ? null : body.remark()), userId);
        return ApiResponse.ok();
    }

    public record StageStatusBody(Integer status) {
    }

    @PostMapping("/stages/{id}/status")
    public ApiResponse<Void> changeStageStatus(@PathVariable long id, @RequestBody StageStatusBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        targets.changeStageStatus(orgId, id, body == null || body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/stages/{id}")
    public ApiResponse<Void> deleteStage(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        targets.deleteStage(orgId, id);
        return ApiResponse.ok();
    }

    @GetMapping("/achievement")
    public ApiResponse<Map<String, Object>> achievement(@RequestParam long node,
            @RequestParam String ym, @RequestParam(required = false) String line,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(targets.achievement(orgId, node, line, ym));
    }
}
