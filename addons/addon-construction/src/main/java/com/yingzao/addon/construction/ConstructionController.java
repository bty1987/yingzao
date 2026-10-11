package com.yingzao.addon.construction;

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
 * 施工端点:组织配置 + 节点模板 + 放线三段(申请/排期/成功/失败/显式补录)
 * + 施工单(分页/详情/监理/辅料开关)+ 节点推进(开工/完工/工费旁路/免检/删除/重排)
 * + 团队留痕(工人/进出场/日志)+ 验收(整改分支+竣工唯一)。
 * 比例 ‰、工期 ‰天、金额 ‰元(厘);免检必须带理由留痕。
 */
@RestController
@RequestMapping("/api/construction")
public class ConstructionController {

    public record NodeRequest(String name, Integer sort, String process, Integer precheck, Integer postcheck,
            Integer stacheck, Integer fincheck, Integer timeLimit, Long salaryLimit, Long accessoryLimit,
            String startTalk, String endTalk) {
    }

    public record ConfigRequest(Integer acceptanceFloor) {
    }

    public record DrawlineApplyRequest(Long orderId) {
    }

    public record DrawlineScheduleRequest(String groupName, String planStart, String planEnd) {
    }

    public record DrawlineFailRequest(String remark) {
    }

    public record RetroactiveRequest(Long orderId, String groupName, String planStart, String planEnd,
            String remark) {
    }

    public record CheckoutRequest(String reason) {
    }

    public record ResetRequest(Long anchorScheduleId, String anchorTime) {
    }

    public record SupervisorRequest(Long userId) {
    }

    public record AccessoryRequest(Boolean lock) {
    }

    public record WorkerRequest(Long userId) {
    }

    public record RateRequest(Integer score1, Integer score2, Integer score3, String assessment) {
    }

    public record DiaryRequest(String content, Boolean hasDrawing) {
    }

    public record AcceptanceRequest(List<ConstructionService.AcceptanceItemCmd> items, String remark) {
    }

    public record AccessoryLimitRequest(Long accessoryLimit) {
    }

    public record AccessoryQuotaRequest(Long goodsId, Integer checkup, Long numLimit) {
    }

    private final ConstructionService constructions;
    private final ConstructionTeamService teams;
    private final ConstructionAccessoryService accessoryQuotas;
    private final org.springframework.context.MessageSource messages;

    public ConstructionController(ConstructionService constructions, ConstructionTeamService teams,
            ConstructionAccessoryService accessoryQuotas,
            org.springframework.context.MessageSource messages) {
        this.constructions = constructions;
        this.teams = teams;
        this.accessoryQuotas = accessoryQuotas;
        this.messages = messages;
    }

    // ---- 组织配置 ----

    @GetMapping("/config")
    public ApiResponse<Object> getConfig(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.getConfig(orgId));
    }

    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(@RequestBody ConfigRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        constructions.updateConfig(orgId, body == null ? null : body.acceptanceFloor());
        return ApiResponse.ok();
    }

    // ---- 节点模板 ----

    @GetMapping("/nodes")
    public ApiResponse<Object> listNodes(@RequestParam(required = false, defaultValue = "false") boolean includeDisabled,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.listNodes(orgId, includeDisabled));
    }

    @PostMapping("/nodes")
    public ApiResponse<Long> createNode(@RequestBody NodeRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(constructions.createNode(orgId, toNodeCmd(body), userId));
    }

    @PutMapping("/nodes/{id}")
    public ApiResponse<Void> updateNode(@PathVariable long id, @RequestBody NodeRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        constructions.updateNode(orgId, id, toNodeCmd(body));
        return ApiResponse.ok();
    }

    @PostMapping("/nodes/{id}/status")
    public ApiResponse<Void> updateNodeStatus(@PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Object status = body == null ? null : body.get("status");
        constructions.updateNodeStatus(orgId, id, status instanceof Number n ? n.intValue() : null);
        return ApiResponse.ok();
    }

    // ---- 放线 ----

    @PostMapping("/drawlines")
    public ApiResponse<Long> applyDrawline(@RequestBody DrawlineApplyRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null && body.orderId() != null);
        return ApiResponse.ok(constructions.applyDrawline(orgId, body.orderId(), userId));
    }

    @PostMapping("/drawlines/{id}/schedule")
    public ApiResponse<Void> scheduleDrawline(@PathVariable long id, @RequestBody DrawlineScheduleRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        constructions.scheduleDrawline(orgId, id, body == null ? null : body.groupName(),
                body == null ? null : body.planStart(), body == null ? null : body.planEnd());
        return ApiResponse.ok();
    }

    @PostMapping("/drawlines/{id}/success")
    public ApiResponse<Void> successDrawline(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.successDrawline(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/drawlines/{id}/fail")
    public ApiResponse<Void> failDrawline(@PathVariable long id, @RequestBody DrawlineFailRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.failDrawline(orgId, id, body == null ? null : body.remark(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/drawlines/retroactive")
    public ApiResponse<Void> retroactive(@RequestBody RetroactiveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null && body.orderId() != null);
        constructions.retroactiveDrawline(orgId, body.orderId(), body.groupName(), body.planStart(),
                body.planEnd(), body.remark(), userId);
        return ApiResponse.ok();
    }

    // ---- 施工单 ----

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerName, @RequestParam(required = false) Integer process,
            @RequestParam(required = false) Long supervisorId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.page(orgId, orderNo, customerName, process, supervisorId,
                page, size));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<Object> detail(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.detail(orgId, orderId));
    }

    @PutMapping("/orders/{orderId}/supervisor")
    public ApiResponse<Void> updateSupervisor(@PathVariable long orderId, @RequestBody SupervisorRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null && body.userId() != null);
        constructions.updateSupervisor(orgId, orderId, body.userId(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{orderId}/accessory")
    public ApiResponse<Void> switchAccessory(@PathVariable long orderId, @RequestBody AccessoryRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.switchAccessory(orgId, orderId, body != null && Boolean.TRUE.equals(body.lock()), userId);
        return ApiResponse.ok();
    }

    // ---- 节点推进 ----

    @GetMapping("/orders/{orderId}/schedules")
    public ApiResponse<Object> listSchedules(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.detail(orgId, orderId).get("schedules"));
    }

    @PostMapping("/orders/{orderId}/schedules/reset")
    public ApiResponse<Void> resetSchedules(@PathVariable long orderId, @RequestBody ResetRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.resetSchedules(orgId, orderId, body == null ? null : body.anchorScheduleId(),
                body == null ? null : body.anchorTime(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/schedules/{id}/begin")
    public ApiResponse<Void> begin(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.beginSchedule(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/schedules/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.finishSchedule(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/schedules/{id}/open-salary")
    public ApiResponse<Void> openSalary(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.openSalary(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/schedules/{id}/checkout")
    public ApiResponse<Void> checkout(@PathVariable long id, @RequestBody CheckoutRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.checkout(orgId, id, body == null ? null : body.reason(), userId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/schedules/{id}")
    public ApiResponse<Void> deleteSchedule(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.deleteSchedule(orgId, id, userId);
        return ApiResponse.ok();
    }

    // ---- 团队留痕 ----

    @PostMapping("/schedules/{id}/workers")
    public ApiResponse<Long> addWorker(@PathVariable long id, @RequestBody WorkerRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null && body.userId() != null);
        return ApiResponse.ok(teams.addWorker(orgId, id, body.userId(), userId));
    }

    @GetMapping("/schedules/{id}/workers")
    public ApiResponse<Object> listWorkers(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(teams.listWorkers(orgId, id));
    }

    @PostMapping("/workers/{id}/disable")
    public ApiResponse<Void> disableWorker(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        teams.disableWorker(orgId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/workers/{id}/rate-link")
    public ApiResponse<String> sendRateLink(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(teams.sendRateLink(orgId, id));
    }

    @PostMapping("/workers/{id}/rate")
    public ApiResponse<Void> rate(@PathVariable long id, @RequestBody RateRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        teams.rate(orgId, id, body == null ? null : body.score1(), body == null ? null : body.score2(),
                body == null ? null : body.score3(), body == null ? null : body.assessment());
        return ApiResponse.ok();
    }

    @PostMapping("/workers/{id}/enter")
    public ApiResponse<Void> enter(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        teams.enter(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/workers/{id}/leave")
    public ApiResponse<Void> leave(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        teams.leave(orgId, id, userId);
        return ApiResponse.ok();
    }

    @GetMapping("/workers/{id}/records")
    public ApiResponse<Object> listRecords(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(teams.listRecords(orgId, id));
    }

    @PostMapping("/schedules/{id}/diaries")
    public ApiResponse<Long> addDiary(@PathVariable long id, @RequestBody DiaryRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(teams.addDiary(orgId, id, body == null ? null : body.content(),
                body == null ? null : body.hasDrawing(), userId));
    }

    @GetMapping("/schedules/{id}/diaries")
    public ApiResponse<Object> listDiaries(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(teams.listDiaries(orgId, id));
    }

    @GetMapping("/orders/{orderId}/diaries")
    public ApiResponse<Object> listOrderDiaries(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(teams.listOrderDiaries(orgId, orderId));
    }

    // ---- 验收 ----

    @PostMapping("/orders/{orderId}/acceptance")
    public ApiResponse<Void> submitAcceptance(@PathVariable long orderId, @RequestBody AcceptanceRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        constructions.submitAcceptance(orgId, orderId, body == null ? null : body.items(),
                body == null ? null : body.remark(), userId);
        return ApiResponse.ok();
    }

    @GetMapping("/orders/{orderId}/acceptance")
    public ApiResponse<Object> getAcceptance(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(constructions.getAcceptance(orgId, orderId));
    }

    // ---- 辅料额度(V602;额度闸/台账由本域持有,消费方 addon-inventory 经契约同步调用) ----

    @GetMapping("/orders/{orderId}/accessory")
    public ApiResponse<Object> getAccessory(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accessoryQuotas.getAccessory(orgId, orderId));
    }

    @PutMapping("/orders/{orderId}/accessory-limit")
    public ApiResponse<Void> updateAccessoryLimit(@PathVariable long orderId,
            @RequestBody AccessoryLimitRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accessoryQuotas.updateLimit(orgId, orderId, body == null ? null : body.accessoryLimit());
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{orderId}/accessory-quotas")
    public ApiResponse<Void> saveAccessoryQuota(@PathVariable long orderId,
            @RequestBody AccessoryQuotaRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accessoryQuotas.saveQuota(orgId, orderId, body == null ? null : body.goodsId(),
                body == null ? null : body.checkup(), body == null ? null : body.numLimit());
        return ApiResponse.ok();
    }

    @DeleteMapping("/orders/{orderId}/accessory-quotas/{goodsId}")
    public ApiResponse<Void> deleteAccessoryQuota(@PathVariable long orderId, @PathVariable long goodsId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accessoryQuotas.deleteQuota(orgId, orderId, goodsId);
        return ApiResponse.ok();
    }

    @GetMapping("/orders/{orderId}/accessory-ledger")
    public ApiResponse<Object> accessoryLedger(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accessoryQuotas.ledger(orgId, orderId));
    }

    private static ConstructionService.NodeCmd toNodeCmd(NodeRequest body) {
        if (body == null) {
            return null;
        }
        return new ConstructionService.NodeCmd(body.name(), body.sort(), body.process(), body.precheck(),
                body.postcheck(), body.stacheck(), body.fincheck(), body.timeLimit(), body.salaryLimit(),
                body.accessoryLimit(), body.startTalk(), body.endTalk());
    }

    private void require(boolean cond) {
        if (!cond) {
            throw new com.yingzao.core.web.BizException(ConstructionService.CODE_PARAM,
                    messages.getMessage("construction.param.invalid", null,
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
    }
}
