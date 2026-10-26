package com.yingzao.addon.decoration;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 装修单端点:登记/分页/详情/补充信息 + 合同线流转(预签/签约/重签/废单/终止审批/决算/完工)
 * + 确认线(合同确认含微改大改变体/放线确认)+ 量房(申请/成功/失败)+ 转归属。
 * 金额一律厘;合同线迁移白名单外统一 250002。
 */
@RestController
@RequestMapping("/api/decoration")
public class DecorationController {

    public record CreateRequest(Long customerId, Long houseId, Integer type, Long designerId, Long salesmanId,
            Long depositAmount, Long innerArea, String houseType, String style, String note, String remark) {
    }

    public record SignRequest(Long contractAmount) {
    }

    public record TerminateRequest(Long assigneeId, String reason) {
    }

    public record DesignConfirmRequest(Integer variant) {
    }

    public record MeasureApplyRequest(String planTime, String remark) {
    }

    public record MeasureFailRequest(String remark) {
    }

    public record MidpackRequest(Integer materialRate, Integer serviceRate, Long materialPrice,
            String remark) {
    }

    public record HolonomyRequest(Long discountBase, Integer discount, Integer manageFollow,
            Integer maching, Integer serviceRate, String remark) {
    }

    public record InclusiveRequest(Long commonUndis, Long commonDis, Long commonManage, Long commonTax,
            Long metUndis, Long metDis, Long metManage, Long metTax, Integer metFlag, String remark) {
    }

    public record CaliberKindRequest(String kind) {
    }

    public record TransferBelongRequest(Integer line, Long userId) {
    }

    public record ProfileRequest(Long innerArea, String houseType, String style, String note) {
    }

    /** 决算六项检查组织配置(九列白名单,null=保留现值)。 */
    public record AccountConfigRequest(Boolean checkEnable, Boolean signEnable, Boolean salesEnable,
            Boolean constructionEnable, Boolean selectionEnable, Boolean decoPaymentEnable,
            Boolean salePaymentEnable, Integer decoPaymentTarget, Integer salePaymentTarget) {
    }

    private final DecorationService orders;
    private final DecorationAccountCheckService accountChecks;
    private final CaliberService calibers;

    public DecorationController(DecorationService orders, DecorationAccountCheckService accountChecks,
            CaliberService calibers) {
        this.orders = orders;
        this.accountChecks = accountChecks;
        this.calibers = calibers;
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerName, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer type, @RequestParam(required = false) Long designerId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(orders.page(orgId, orderNo, customerName, status, type, designerId, page, size));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(orders.detail(orgId, id));
    }

    @PostMapping("/orders")
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(orders.create(orgId, new DecorationService.CreateCmd(
                nvl(body.customerId()), nvl(body.houseId()), body.type(), nvl(body.designerId()),
                nvl(body.salesmanId()), body.depositAmount(), body.innerArea(), body.houseType(),
                body.style(), body.note(), body.remark()), userId));
    }

    @PostMapping("/orders/{id}/profile")
    public ApiResponse<Void> saveProfile(@PathVariable long id, @RequestBody ProfileRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.saveProfile(orgId, id, new DecorationService.ProfileFields(
                body == null ? null : body.innerArea(), body == null ? null : body.houseType(),
                body == null ? null : body.style(), body == null ? null : body.note()), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/presign")
    public ApiResponse<Void> presign(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.presign(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/sign")
    public ApiResponse<Void> sign(@PathVariable long id, @RequestBody SignRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.sign(orgId, id, body == null ? null : body.contractAmount(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/resign")
    public ApiResponse<Void> resign(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.resign(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/obsolete")
    public ApiResponse<Void> obsolete(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.obsolete(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/terminate")
    public ApiResponse<Long> terminate(@PathVariable long id, @RequestBody TerminateRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(orders.submitTermination(orgId, id,
                body == null ? null : body.assigneeId(), body == null ? null : body.reason(), userId));
    }

    /** 决算预检(只读):六子项状态/明细/target,前端决算确认弹窗数据源。 */
    @GetMapping("/orders/{id}/account-check")
    public ApiResponse<Map<String, Object>> accountCheck(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accountChecks.preview(orgId, id));
    }

    // ---- 组织配置(决算六项检查) ----

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(accountChecks.getConfig(orgId));
    }

    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(@RequestBody AccountConfigRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        accountChecks.updateConfig(orgId, body.checkEnable(), body.signEnable(), body.salesEnable(),
                body.constructionEnable(), body.selectionEnable(), body.decoPaymentEnable(),
                body.salePaymentEnable(), body.decoPaymentTarget(), body.salePaymentTarget());
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/account")
    public ApiResponse<Void> account(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.account(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.finish(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/design-confirm")
    public ApiResponse<Void> designConfirm(@PathVariable long id, @RequestBody DesignConfirmRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.designConfirm(orgId, id, body == null ? null : body.variant(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/drawline-confirm")
    public ApiResponse<Void> drawlineConfirm(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.drawlineConfirm(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/measures")
    public ApiResponse<Long> measureApply(@PathVariable long id, @RequestBody MeasureApplyRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(orders.measureApply(orgId, id,
                body == null ? null : body.planTime(), body == null ? null : body.remark(), userId));
    }

    @PostMapping("/measures/{id}/success")
    public ApiResponse<Void> measureSuccess(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.measureSuccess(orgId, id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/measures/{id}/fail")
    public ApiResponse<Void> measureFail(@PathVariable long id, @RequestBody MeasureFailRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.measureFail(orgId, id, body == null ? null : body.remark(), userId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{id}/belong/transfer")
    public ApiResponse<Void> transferBelong(@PathVariable long id, @RequestBody TransferBelongRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        orders.transferBelong(orgId, id, body == null ? null : body.line(),
                body == null || body.userId() == null ? 0 : body.userId(), userId);
        return ApiResponse.ok();
    }

    // ---- 三口径表(A6:半包配比/整装满减/包干增项;make 幂等重建,confirm 冻结) ----

    @PostMapping("/orders/{id}/calibers/midpack")
    public ApiResponse<Map<String, Object>> makeMidpack(@PathVariable long id,
            @RequestBody MidpackRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(calibers.makeMidpack(orgId, id, body == null ? null
                : new CaliberService.MidpackCmd(body.materialRate(), body.serviceRate(),
                        body.materialPrice(), body.remark()), userId));
    }

    @PostMapping("/orders/{id}/calibers/holonomy")
    public ApiResponse<Map<String, Object>> makeHolonomy(@PathVariable long id,
            @RequestBody HolonomyRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(calibers.makeHolonomy(orgId, id, body == null ? null
                : new CaliberService.HolonomyCmd(body.discountBase(), body.discount(),
                        body.manageFollow(), body.maching(), body.serviceRate(), body.remark()),
                userId));
    }

    @PostMapping("/orders/{id}/calibers/inclusive")
    public ApiResponse<Map<String, Object>> makeInclusive(@PathVariable long id,
            @RequestBody InclusiveRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(calibers.makeInclusive(orgId, id, body == null ? null
                : new CaliberService.InclusiveCmd(body.commonUndis(), body.commonDis(),
                        body.commonManage(), body.commonTax(), body.metUndis(), body.metDis(),
                        body.metManage(), body.metTax(), body.metFlag(), body.remark()), userId));
    }

    @PostMapping("/orders/{id}/calibers/confirm")
    public ApiResponse<Void> confirmCaliber(@PathVariable long id, @RequestBody CaliberKindRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        calibers.confirm(orgId, body == null ? null : body.kind(), id, userId);
        return ApiResponse.ok();
    }

    @GetMapping("/orders/{id}/calibers")
    public ApiResponse<Map<String, Object>> listCalibers(@PathVariable long id,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(calibers.all(orgId, id));
    }

    private static long nvl(Long value) {
        return value == null ? 0 : value;
    }
}
