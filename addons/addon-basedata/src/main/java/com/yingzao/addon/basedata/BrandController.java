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

/**
 * 品牌字典端点(行业公共字典,无组织维度)。
 */
@RestController
@RequestMapping("/api/basedata")
public class BrandController {

    public record CreateBrandRequest(String name, String pinyin) {
    }

    public record RenameBrandRequest(String name) {
    }

    public record UpdateStatusRequest(Integer status) {
    }

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @GetMapping("/brands")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(brandService.list(keyword, includeDisabled));
    }

    @PostMapping("/brands")
    public ApiResponse<Long> create(@RequestBody CreateBrandRequest body) {
        return ApiResponse.ok(brandService.create(body.name(), body.pinyin()));
    }

    @PutMapping("/brands/{id}")
    public ApiResponse<Void> rename(@PathVariable long id, @RequestBody RenameBrandRequest body) {
        brandService.rename(id, body.name());
        return ApiResponse.ok();
    }

    @PostMapping("/brands/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest body) {
        brandService.updateStatus(id, body.status());
        return ApiResponse.ok();
    }
}
