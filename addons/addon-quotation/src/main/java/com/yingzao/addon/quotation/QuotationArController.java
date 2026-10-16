package com.yingzao.addon.quotation;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 增减差量单端点(230100 段;RBAC 归 /api/quotation prefix 菜单闸,零新增声明)。
 * 全部操作面向「合同后变更」;详细设计 docs/prd/addon-quotation-addreduce.md。
 */
@RestController
@RequestMapping("/api/quotation/ar")
public class QuotationArController {

    public record ArItemBody(Integer itemType, Long refQiid, Long goodsId, String goodsName,
            String unit, Long unitPrice, Long num) {
    }

    public record ArBody(Long orderId, java.util.List<ArItemBody> items, String remark) {
    }

    public record ArUpdateBody(java.util.List<ArItemBody> items, String remark) {
    }

    private final QuotationAddReduceService addReduce;

    public QuotationArController(QuotationAddReduceService addReduce) {
        this.addReduce = addReduce;
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }

    @PostMapping
    public ApiResponse<Long> open(@RequestBody ArBody body, HttpServletRequest request) {
        return ApiResponse.ok(addReduce.open(orgId(request),
                body == null ? null : body.orderId(),
                body == null ? null : body.items().stream()
                        .map(i -> new QuotationAddReduceService.ArItemCmd(i.itemType(), i.refQiid(),
                                i.goodsId(), i.goodsName(), i.unit(), i.unitPrice(), i.num()))
                        .toList(),
                body == null ? null : body.remark(), userId(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody ArUpdateBody body,
            HttpServletRequest request) {
        addReduce.updateDraft(orgId(request), id,
                body == null ? null : body.items().stream()
                        .map(i -> new QuotationAddReduceService.ArItemCmd(i.itemType(), i.refQiid(),
                                i.goodsId(), i.goodsName(), i.unit(), i.unitPrice(), i.num()))
                        .toList(),
                body == null ? null : body.remark(), userId(request));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDraft(@PathVariable long id, HttpServletRequest request) {
        addReduce.deleteDraft(orgId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Long> submit(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(addReduce.submit(orgId(request), id, userId(request)));
    }

    @PostMapping("/{id}/cancel-submit")
    public ApiResponse<Void> cancelSubmit(@PathVariable long id, HttpServletRequest request) {
        addReduce.cancelSubmit(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long id, HttpServletRequest request) {
        addReduce.confirm(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(addReduce.get(orgId(request), id));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(addReduce.page(orgId(request), orderId, status, page, size));
    }
}
