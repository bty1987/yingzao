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
 * 工程档案端点(两族合一):模板(头+项+选项三层)/ 实例化 / 逐项回写 / 完成。
 * mount 挂载点:0 放线档案/1 施工档案/2 验收检查项模板。
 */
@RestController
@RequestMapping("/api/construction")
public class ConstructionProfileController {

    public record TemplateRequest(Integer mount, String name) {
    }

    public record ItemRequest(String name, Integer type, Integer require, String content, Integer sort,
            List<String> optionTitles) {
    }

    public record InstantiateRequest(Long templateId) {
    }

    public record FillRequest(String content, List<Long> checkedOptionIds) {
    }

    private final ConstructionProfileService profiles;
    private final org.springframework.context.MessageSource messages;

    public ConstructionProfileController(ConstructionProfileService profiles,
            org.springframework.context.MessageSource messages) {
        this.profiles = profiles;
        this.messages = messages;
    }

    @GetMapping("/profile-templates")
    public ApiResponse<Object> listTemplates(@RequestParam(required = false) Integer mount,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(profiles.listTemplates(orgId, mount));
    }

    @PostMapping("/profile-templates")
    public ApiResponse<Long> createTemplate(@RequestBody TemplateRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null);
        return ApiResponse.ok(profiles.createTemplate(orgId, body.mount(), body.name(), userId));
    }

    @DeleteMapping("/profile-templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        profiles.deleteTemplate(orgId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/profile-templates/{id}/items")
    public ApiResponse<Long> addTemplateItem(@PathVariable long id, @RequestBody ItemRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        require(body != null);
        return ApiResponse.ok(profiles.addTemplateItem(orgId, id, toItemCmd(body)));
    }

    @DeleteMapping("/profile-templates/{id}/items/{itemId}")
    public ApiResponse<Void> deleteTemplateItem(@PathVariable long id, @PathVariable long itemId,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        profiles.deleteTemplateItem(orgId, id, itemId);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{orderId}/profiles")
    public ApiResponse<Long> instantiate(@PathVariable long orderId, @RequestBody InstantiateRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        require(body != null && body.templateId() != null);
        return ApiResponse.ok(profiles.instantiate(orgId, orderId, body.templateId(), userId));
    }

    @GetMapping("/orders/{orderId}/profiles")
    public ApiResponse<Object> listOrderProfiles(@PathVariable long orderId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(profiles.listOrderProfiles(orgId, orderId));
    }

    @PutMapping("/profiles/{id}/items/{itemId}")
    public ApiResponse<Void> fillItem(@PathVariable long id, @PathVariable long itemId,
            @RequestBody FillRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        profiles.fillItem(orgId, id, itemId, body == null ? null : body.content(),
                body == null ? null : body.checkedOptionIds());
        return ApiResponse.ok();
    }

    @PostMapping("/profiles/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        profiles.complete(orgId, id);
        return ApiResponse.ok();
    }

    private ConstructionProfileService.ItemCmd toItemCmd(ItemRequest body) {
        return new ConstructionProfileService.ItemCmd(body.name(), body.type(), body.require(),
                body.content(), body.sort(), body.optionTitles());
    }

    private void require(boolean cond) {
        if (!cond) {
            throw new com.yingzao.core.web.BizException(ConstructionService.CODE_PARAM,
                    messages.getMessage("construction.param.invalid", null,
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
    }
}
