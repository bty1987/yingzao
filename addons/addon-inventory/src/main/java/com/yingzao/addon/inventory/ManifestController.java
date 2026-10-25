package com.yingzao.addon.inventory;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 出入库工作台端点:手工建单(进货/出货)/确认/初始态删行/冲销(进退/出返)/
 * 调拨审批提交/盘盈亏登记/单据分页与详情。
 */
@RestController
@RequestMapping("/api/inventory/manifests")
public class ManifestController {

    public record ManualRequest(Integer type, Long orderId, List<ManifestService.ItemCmd> items,
            String remark) {
    }

    public record ReversalRequest(Integer type, Long sourceManifestId,
            List<ManifestService.ReversalLineCmd> lines, String remark) {
    }

    public record TransferRequest(List<ManifestService.TransferLineCmd> lines, Long assigneeId,
            String remark) {
    }

    public record AdjustRequest(Integer type, List<ManifestService.AdjustItemCmd> items,
            String remark) {
    }

    public record PackageOpRequest(Long packageGoodsId, Long colorId, Long formatId,
            Long warehouseId, Long num) {
    }

    private final ManifestService manifests;

    public ManifestController(ManifestService manifests) {
        this.manifests = manifests;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Integer type,
            @RequestParam(required = false) Integer status, @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(manifests.page(orgId, type, status, code, page, size));
    }

    @PostMapping
    public ApiResponse<Long> createManual(@RequestBody ManualRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.createManual(orgId, body == null ? null : body.type(),
                body == null ? null : body.items(), body == null ? null : body.remark(),
                body == null ? null : body.orderId(), userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> detail(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(manifests.detail(orgId, id));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        manifests.confirm(orgId, id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ApiResponse<Void> removeItem(@PathVariable long id, @PathVariable long itemId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        manifests.removeItem(orgId, id, itemId, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/reversals")
    public ApiResponse<Long> createReversal(@RequestBody ReversalRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.createReversal(orgId, body == null ? null : body.type(),
                body == null ? null : body.sourceManifestId(), body == null ? null : body.lines(),
                body == null ? null : body.remark(), userId));
    }

    @PostMapping("/transfers")
    public ApiResponse<Long> submitTransfer(@RequestBody TransferRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.submitTransfer(orgId, body == null ? null : body.lines(),
                body == null ? null : body.assigneeId(), body == null ? null : body.remark(),
                userId));
    }

    @PostMapping("/adjustments")
    public ApiResponse<Long> adjust(@RequestBody AdjustRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.adjust(orgId, body == null ? null : body.type(),
                body == null ? null : body.items(), body == null ? null : body.remark(), userId));
    }

    /** 套拆(10 型):出套餐+入成员,数量=套餐数×成员量;落单即确认。 */
    @PostMapping("/package-split")
    public ApiResponse<Long> packageSplit(@RequestBody PackageOpRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.packageOp(orgId, ManifestService.TYPE_PS,
                body == null ? null : new ManifestService.PackageOpCmd(body.packageGoodsId(),
                        body.colorId(), body.formatId(), body.warehouseId(), body.num()), userId));
    }

    /** 套合(9 型):出成员+入套餐;落单即确认。 */
    @PostMapping("/package-merge")
    public ApiResponse<Long> packageMerge(@RequestBody PackageOpRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(manifests.packageOp(orgId, ManifestService.TYPE_PM,
                body == null ? null : new ManifestService.PackageOpCmd(body.packageGoodsId(),
                        body.colorId(), body.formatId(), body.warehouseId(), body.num()), userId));
    }
}
