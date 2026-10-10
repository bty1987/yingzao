package com.yingzao.core.addon;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

/**
 * 前端数据源:已启用插件清单 + 槽位挂载声明 + 字典。
 * Web 后台启动时拉取,据此动态注册路由、菜单与槽位组件。
 */
@RestController
public class AddonController {

    private final AddonRegistry registry;

    public AddonController(AddonRegistry registry) {
        this.registry = registry;
    }

    public record AddonView(String id, String name, String version,
                            List<AddonDescriptor.Menu> menus,
                            List<AddonDescriptor.Mount> mounts, Object dicts) {
    }

    @GetMapping("/api/addons")
    public ApiResponse<List<AddonView>> addons() {
        List<AddonView> views = registry.enabled().stream()
                .map(d -> new AddonView(d.id(), d.name(), d.version(), d.menus(), d.mounts(), d.dicts()))
                .toList();
        return ApiResponse.ok(views);
    }
}
