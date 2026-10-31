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
        List<Mobile> mobile,
        List<ApprovalForm> approvalForms,
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
     * 移动端工作台宫格槽位声明(docs/15 R14):icon=宋体单字方章字/sub=一行说明/page=app 内页路由/
     * sort=跨插件排序。require 沿 api 段语义——菜单 path 或 capability;为空 = 人人可见;
     * 下发时按当前用户授权过滤,无权限格子不出现。
     */
    public record Mobile(String icon, String title, String sub, String page, String require, int sort) {
    }

    /**
     * 审批表单模板声明(docs/15 R15~R16):插件注册「员工可在移动端发起的审批表单」schema——
     * key=发起时审批单的 biz_type 锚;submit=表单提交端点(插件业务端点,RBAC 自行声明于 api 段);
     * fields 原样透传(id/label/type/source/fill/required 等),内核不认业务字段,渲染解释全部在移动端。
     * require 沿 mobile 段语义=菜单 path 或 capability,为空=人人可见;下发时按当前用户授权过滤。
     */
    public record ApprovalForm(String key, String title, String icon, String sub, String submit,
            String require, int sort, List<Map<String, Object>> fields) {
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
