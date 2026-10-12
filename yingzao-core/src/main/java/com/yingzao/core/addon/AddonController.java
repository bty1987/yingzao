package com.yingzao.core.addon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 前端数据源:已启用插件清单 + 槽位挂载声明 + 字典。
 * Web 后台启动时拉取,据此动态注册路由、菜单与槽位组件。
 * 需登录;返回按当前用户过滤:管理员旁路;普通用户 = 其全部角色的菜单/功能点授权并集,
 * 过滤后既无菜单也无挂载的插件整体不出现(字典随插件条目下发)。
 */
@RestController
public class AddonController {

    private final AddonRegistry registry;
    private final RoleService roleService;

    public AddonController(AddonRegistry registry, RoleService roleService) {
        this.registry = registry;
        this.roleService = roleService;
    }

    public record AddonView(String id, String name, String version,
                            List<AddonDescriptor.Menu> menus,
                            List<AddonDescriptor.Mount> mounts,
                            List<ApiView> apis, Object dicts) {
    }

    /** API 鉴权声明下发(脱敏到 pattern/require):前端可据此做按钮级显隐,显隐不是闸门。 */
    public record ApiView(String prefix, String exact, String require, List<String> methods) {
    }

    @GetMapping("/api/addons")
    public ApiResponse<List<AddonView>> addons(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        RoleService.Grants grants = roleService.grantsCached(orgId, userId);
        boolean admin = grants.admin();
        List<AddonView> views = new ArrayList<>();
        for (AddonDescriptor d : registry.enabled()) {
            List<AddonDescriptor.Menu> menus = d.menus();
            List<AddonDescriptor.Mount> mounts = d.mounts();
            if (!admin) {
                menus = d.menus().stream().filter(m -> grants.menus().contains(m.path())).toList();
                // capability 为空的挂载 = 无权限诉求,人人可见
                mounts = d.mounts().stream()
                        .filter(m -> m.capability() == null || m.capability().isBlank()
                                || grants.capabilities().contains(m.capability()))
                        .toList();
            }
            if (menus.isEmpty() && mounts.isEmpty()) {
                continue;
            }
            List<ApiView> apis = d.apis() == null ? List.of() : d.apis().stream()
                    .map(a -> new ApiView(a.prefix(), a.exact(), a.require(), a.methods()))
                    .toList();
            views.add(new AddonView(d.id(), d.name(), d.version(), menus, mounts, apis, d.dicts()));
        }
        return ApiResponse.ok(views);
    }
}
