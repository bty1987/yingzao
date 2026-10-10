package com.yingzao.core.addon;

import java.util.List;
import java.util.Map;

/**
 * addon.yml 的结构化描述。
 */
public record AddonDescriptor(
        String id,
        String name,
        String version,
        int errorCodeSegment,
        List<Menu> menus,
        List<Mount> mounts,
        Map<String, Object> dicts) {

    /** 插件菜单声明:前端据此动态注册路由与侧栏菜单项。 */
    public record Menu(String path, String title, String icon) {
    }

    /** 前端槽位挂载声明:component 由插件前端包导出,capability 为渲染所需权限。 */
    public record Mount(String slot, String component, String capability) {
    }
}
