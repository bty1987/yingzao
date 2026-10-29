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

import com.yingzao.addon.target.PkService.DraftCmd;
import com.yingzao.addon.target.PkService.EventCmd;
import com.yingzao.addon.target.PkService.LockCmd;
import com.yingzao.addon.target.PkService.UnitCmd;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * PK 对赌端点(/api/target/pk;鉴权走内核 RbacInterceptor + addon.yml api 段,独立菜单闸 /target/pk)。
 * 状态机:0 筹备 →(首次锁档)1 对赌中 →(confirm)2 进行中 →(归档任务)3 已归档;
 * 0/1 可作废(4 终态);归档走 yz_plan target-pk-archive,快照时点=归档时点。
 */
@RestController
@RequestMapping("/api/target/pk")
public class TargetPkController {

    private final PkService pks;
    private final PkArchivePlanHandler archivePlans;

    public TargetPkController(PkService pks, PkArchivePlanHandler archivePlans) {
        this.pks = pks;
        this.archivePlans = archivePlans;
    }

    public record EventBody(Integer cycleType, String period, String name, String startDate,
            String endDate, Integer baseTier, Integer tierIncrement) {
    }

    @GetMapping("/events")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer cycleType,
            @RequestParam(required = false) String period, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(pks.listEvents(orgId, status, cycleType, period));
    }

    @PostMapping("/events")
    public ApiResponse<Long> create(@RequestBody EventBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(pks.createEvent(orgId, toCmd(body), userId));
    }

    /** 从上一届复制赛事(含单元模板;锁档/实绩清零)。 */
    @PostMapping("/events/{id}/copy")
    public ApiResponse<Long> copy(@PathVariable long id, @RequestBody EventBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(pks.copyEvent(orgId, id, toCmd(body), userId));
    }

    @GetMapping("/events/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(pks.detail(orgId, id));
    }

    public record DraftBody(List<UnitCmd> units) {
    }

    /** 保存整包模板草稿(仅筹备态)。 */
    @PutMapping("/events/{id}/draft")
    public ApiResponse<Void> saveDraft(@PathVariable long id, @RequestBody DraftBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.saveDraft(orgId, id, body == null ? null : new DraftCmd(body.units()));
        return ApiResponse.ok();
    }

    /** 发布草稿落行(发布后清草稿;返回落行数)。 */
    @PostMapping("/events/{id}/publish")
    public ApiResponse<Integer> publish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(pks.publishDraft(orgId, id, userId));
    }

    @PostMapping("/events/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.confirm(orgId, id);
        archivePlans.ensureScheduled(orgId);
        return ApiResponse.ok();
    }

    @PostMapping("/events/{id}/reset")
    public ApiResponse<Void> reset(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.reset(orgId, id);
        return ApiResponse.ok();
    }

    public record VoidBody(String reason) {
    }

    @PostMapping("/events/{id}/void")
    public ApiResponse<Void> voidEvent(@PathVariable long id, @RequestBody VoidBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.voidEvent(orgId, id, body == null ? null : body.reason());
        return ApiResponse.ok();
    }

    public record UnitBody(Long orgNode, List<String> staffs, Long guarantee, Long base,
            Long breakthrough, Long challenge, Long pkMoney, Long bonusPreset,
            Integer manualEnabled) {
    }

    @PutMapping("/events/{id}/units/{node}")
    public ApiResponse<Void> upsertUnit(@PathVariable long id, @PathVariable long node,
            @RequestBody UnitBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.upsertUnitRow(orgId, id, toUnit(node, body));
        return ApiResponse.ok();
    }

    /** 模板行删除:仅筹备态。 */
    @DeleteMapping("/events/{id}/units/{node}")
    public ApiResponse<Void> deleteUnit(@PathVariable long id, @PathVariable long node,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.deleteUnit(orgId, id, node);
        return ApiResponse.ok();
    }

    public record LockBody(Integer tier, Long customTarget, Long customBonus) {
    }

    /** 锁档:选档(奖金=PK金×档位码)或全自定义;首次锁档翻对赌中。 */
    @PostMapping("/events/{id}/units/{node}/lock")
    public ApiResponse<Void> lock(@PathVariable long id, @PathVariable long node,
            @RequestBody LockBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        pks.lock(orgId, id, node, body == null ? null
                : new LockCmd(body.tier(), body.customTarget(), body.customBonus()), userId);
        return ApiResponse.ok();
    }

    /** 解锁:仅对赌中;未锁档拒。 */
    @PostMapping("/events/{id}/units/{node}/unlock")
    public ApiResponse<Void> unlock(@PathVariable long id, @PathVariable long node,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.unlock(orgId, id, node);
        return ApiResponse.ok();
    }

    public record ManualBody(Long performance, String remark) {
    }

    /** 手工实绩:进行中可录,单元须启用(快照生成即冻结)。 */
    @PutMapping("/events/{id}/units/{node}/manual")
    public ApiResponse<Void> manual(@PathVariable long id, @PathVariable long node,
            @RequestBody ManualBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        pks.saveManual(orgId, id, node, body == null ? null : body.performance(),
                body == null ? null : body.remark());
        return ApiResponse.ok();
    }

    /** 归档快照(未归档返回空)。 */
    @GetMapping("/events/{id}/snapshots")
    public ApiResponse<List<Map<String, Object>>> snapshots(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(pks.snapshots(orgId, id));
    }

    // ---- 内部 ----

    private static EventCmd toCmd(EventBody body) {
        return body == null ? null : new EventCmd(body.cycleType(), body.period(), body.name(),
                body.startDate(), body.endDate(), body.baseTier(), body.tierIncrement());
    }

    private static UnitCmd toUnit(long orgNode, UnitBody body) {
        return body == null ? null : new UnitCmd(orgNode, body.staffs(), body.guarantee(),
                body.base(), body.breakthrough(), body.challenge(), body.pkMoney(),
                body.bonusPreset(), body.manualEnabled());
    }
}
