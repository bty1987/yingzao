package com.yingzao.addon.basedata;

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
 * 品类树端点:平台共享 + 组织私有两层;停用守卫(存在启用子品类须先停用)。
 */
@RestController
@RequestMapping("/api/basedata")
public class CategoryController {

    public record CreateCategoryRequest(String name, Long parentId) {
    }

    public record RenameCategoryRequest(String name) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> tree(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(categoryService.tree(orgId));
    }

    @PostMapping("/categories")
    public ApiResponse<Long> create(@RequestBody CreateCategoryRequest body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(categoryService.create(orgId, body.name(),
                body.parentId() == null ? CategoryService.ROOT : body.parentId()));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<Void> rename(@PathVariable long id, @RequestBody RenameCategoryRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        categoryService.rename(orgId, id, body.name());
        return ApiResponse.ok();
    }

    @PostMapping("/categories/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        categoryService.updateStatus(orgId, id, body.status());
        return ApiResponse.ok();
    }
}
