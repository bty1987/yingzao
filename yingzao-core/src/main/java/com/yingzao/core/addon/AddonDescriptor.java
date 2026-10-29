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
        List<Api> apis,
        List<Metric> metrics,
        Map<String, Object> dicts) {

    /** 插件菜单声明:前端据此动态注册路由与侧栏菜单项。 */
    public record Menu(String path, String title, String icon) {
    }

    /**
     * 指标声明(报表框架 D4):五要素元数据进 addon.yml metrics 段,声明与 MetricProvider
     * 实现分离——name/desc/source 为 i18n 键,Registry 组装时解析为展示文本;
     * status=confirmed 须有 provider 在位,pending=口径确认中(灰灯,可无 provider)。
     */
    public record Metric(String key, String nameKey, String descKey, String sourceKey,
            String frequency, String compare, String unit,
            long thresholdWarn, long thresholdCritical, String status) {
    }

    /** 前端槽位挂载声明:component 由插件前端包导出,capability 为渲染所需权限。 */
    public record Mount(String slot, String component, String capability) {
    }

    /**
     * 业务 API 端点鉴权声明(RBAC v2)。require 三类取值——
     * 菜单 path(授权该菜单的角色可调)/ capability(授权该功能点的角色可调)/
     * authenticated(登录即可)。exact 优先于 prefix,prefix 取最长匹配;
     * methods 可选,默认不限。
     */
    public record Api(String prefix, String exact, String require, List<String> methods) {

        /** 声明的匹配模式(exact 优先)。 */
        public String pattern() {
            return exact != null && !exact.isBlank() ? exact : prefix;
        }

        public boolean exactMatch() {
            return exact != null && !exact.isBlank();
        }
    }
}
