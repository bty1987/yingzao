package com.yingzao.addon.quotation;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.addons.api.NormApi;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 定额子域端点(全部归 /api/quotation 既有 prefix 菜单闸,零新增闸声明——核对清单 8;
 * 沿 QuotationArController 先例)。
 */
@RestController
public class NormController {

    public record ArchiveStatusRequest(Integer status) {
    }

    public record GenerateRequest(Long arId) {
    }

    private final NormArchiveService archives;
    private final NormService norms;
    private final NormApi normApi;

    public NormController(NormArchiveService archives, NormService norms, NormApi normApi) {
        this.archives = archives;
        this.norms = norms;
        this.normApi = normApi;
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }

    // ---- 档案层:三 kind 定额 ----

    @GetMapping("/api/quotation/norms/archives")
    public ApiResponse<Map<String, Object>> pageArchives(@RequestParam(required = false) Integer kind,
            @RequestParam(required = false) Long catalogItemId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(archives.pageArchives(orgId(request), kind, catalogItemId, keyword,
                page, size));
    }

    @PostMapping("/api/quotation/norms/archives")
    public ApiResponse<Long> createArchive(@RequestBody NormArchiveService.ArchiveRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(archives.createArchive(orgId(request), req));
    }

    @PutMapping("/api/quotation/norms/archives/{id}")
    public ApiResponse<Void> updateArchive(@PathVariable long id,
            @RequestBody NormArchiveService.ArchiveRequest req, HttpServletRequest request) {
        archives.updateArchive(orgId(request), id, req);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/quotation/norms/archives/{id}/status")
    public ApiResponse<Void> statusArchive(@PathVariable long id, @RequestBody ArchiveStatusRequest req,
            HttpServletRequest request) {
        archives.statusArchive(orgId(request), id, req.status());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/quotation/norms/archives/{id}")
    public ApiResponse<Void> deleteArchive(@PathVariable long id, HttpServletRequest request) {
        archives.deleteArchive(orgId(request), id);
        return ApiResponse.ok(null);
    }

    // ---- 附加项档案 ----

    @GetMapping("/api/quotation/norms/additions")
    public ApiResponse<Object> listAdditions(@RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        return ApiResponse.ok(archives.listAdditions(orgId(request), keyword));
    }

    @PostMapping("/api/quotation/norms/additions")
    public ApiResponse<Long> createAddition(@RequestBody NormArchiveService.AdditionRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(archives.createAddition(orgId(request), req));
    }

    @PutMapping("/api/quotation/norms/additions/{id}")
    public ApiResponse<Void> updateAddition(@PathVariable long id,
            @RequestBody NormArchiveService.AdditionRequest req, HttpServletRequest request) {
        archives.updateAddition(orgId(request), id, req);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/quotation/norms/additions/{id}/status")
    public ApiResponse<Void> statusAddition(@PathVariable long id,
            @RequestBody ArchiveStatusRequest req, HttpServletRequest request) {
        archives.statusAddition(orgId(request), id, req.status());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/quotation/norms/additions/{id}")
    public ApiResponse<Void> deleteAddition(@PathVariable long id, HttpServletRequest request) {
        archives.deleteAddition(orgId(request), id);
        return ApiResponse.ok(null);
    }

    // ---- 材料定额套餐 ----

    @GetMapping("/api/quotation/norms/packs")
    public ApiResponse<Object> listPacks(HttpServletRequest request) {
        return ApiResponse.ok(archives.listPacks(orgId(request)));
    }

    @PostMapping("/api/quotation/norms/packs")
    public ApiResponse<Long> createPack(@RequestBody NormArchiveService.PackRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(archives.createPack(orgId(request), req));
    }

    @PostMapping("/api/quotation/norms/packs/{id}/status")
    public ApiResponse<Void> statusPack(@PathVariable long id, @RequestBody ArchiveStatusRequest req,
            HttpServletRequest request) {
        archives.statusPack(orgId(request), id, req.status());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/quotation/norms/packs/{id}/items")
    public ApiResponse<Object> listPackItems(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(archives.listPackItems(orgId(request), id));
    }

    @PostMapping("/api/quotation/norms/packs/{id}/items")
    public ApiResponse<Void> savePackItem(@PathVariable long id,
            @RequestBody NormArchiveService.PackItemRequest req, HttpServletRequest request) {
        archives.savePackItem(orgId(request), id, req);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/quotation/norms/packs/{id}/items/{itemId}")
    public ApiResponse<Void> deletePackItem(@PathVariable long id, @PathVariable long itemId,
            HttpServletRequest request) {
        archives.deletePackItem(orgId(request), id, itemId);
        return ApiResponse.ok(null);
    }

    // ---- 实例层:单头/明细/生成/附加确认线 ----

    @GetMapping("/api/quotation/norms/orders/{orderId}")
    public ApiResponse<Map<String, Object>> head(@PathVariable long orderId,
            HttpServletRequest request) {
        Map<String, Object> head = norms.head(orgId(request), orderId);
        head.put("summary", norms.summary(orgId(request), orderId));
        return ApiResponse.ok(head);
    }

    @GetMapping("/api/quotation/norms/orders/{orderId}/items")
    public ApiResponse<Map<String, Object>> items(@PathVariable long orderId,
            @RequestParam(required = false) Integer sourceType,
            @RequestParam(required = false) Integer kind, @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(norms.items(orgId(request), orderId, sourceType, kind, status, page,
                size));
    }

    /** 材料定额汇总(NormApi 契约的 HTTP 投影;领料对账/预算对照;quantity 千分位量纲) */
    @GetMapping("/api/quotation/norms/orders/{orderId}/materials")
    public ApiResponse<Object> materials(@PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.ok(normApi.materialSummaryByOrder(orgId(request), orderId));
    }

    /** 手动重新生成(arId 空=全量;管理动作,老 NormtionAction 收敛) */
    @PostMapping("/api/quotation/norms/orders/{orderId}/generate")
    public ApiResponse<Map<String, Object>> generate(@PathVariable long orderId,
            @RequestBody(required = false) GenerateRequest req, HttpServletRequest request) {
        Long arId = req == null ? null : req.arId();
        return ApiResponse.ok(norms.generate(orgId(request), orderId, arId, userId(request)));
    }

    @PostMapping("/api/quotation/norms/orders/{orderId}/additions")
    public ApiResponse<Long> addAddition(@PathVariable long orderId,
            @RequestBody NormService.AdditionItemRequest req, HttpServletRequest request) {
        return ApiResponse.ok(norms.addAddition(orgId(request), orderId, req, userId(request)));
    }

    @PostMapping("/api/quotation/norms/orders/{orderId}/items/{itemId}/confirm")
    public ApiResponse<Void> confirmAddition(@PathVariable long orderId, @PathVariable long itemId,
            HttpServletRequest request) {
        norms.confirmAddition(orgId(request), orderId, itemId, userId(request), true);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/quotation/norms/orders/{orderId}/items/{itemId}/reject")
    public ApiResponse<Void> rejectAddition(@PathVariable long orderId, @PathVariable long itemId,
            HttpServletRequest request) {
        norms.confirmAddition(orgId(request), orderId, itemId, userId(request), false);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/quotation/norms/orders/{orderId}/items/{itemId}")
    public ApiResponse<Void> deleteAddition(@PathVariable long orderId, @PathVariable long itemId,
            HttpServletRequest request) {
        norms.deleteAddition(orgId(request), orderId, itemId, userId(request));
        return ApiResponse.ok(null);
    }
}
