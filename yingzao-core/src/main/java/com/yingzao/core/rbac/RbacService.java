package com.yingzao.core.rbac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yingzao.core.addon.AddonDescriptor;
import com.yingzao.core.addon.AddonRegistry;

import jakarta.annotation.PostConstruct;

/**
 * RBAC v2 端点鉴权面(内核平台设施):汇总全部启用插件的 addon.yml `api` 段,
 * 构建 exact map + prefix 前缀表;{@link RbacInterceptor} 据此把请求 URI 映射到
 * require 身份(菜单 path / capability)。声明集中、执行集中——插件 Controller 零改动,
 * 映射表改配置即改闸门(修老 functionright 页面按钮与服务端功能字典两套口径的散装实现)。
 *
 * <p>匹配语义:exact 优先于 prefix;prefix 取最长匹配(声明即文档,命中多条按最长者)。
 * 启动期校验(require 指向的菜单/功能点在本插件声明中不存在、prefix 互相包含)只告警不阻断,
 * enforce 全开后升启动失败。
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    /** 一条生效规则:匹配模式 + require 身份 + 所属插件。 */
    public record ApiRule(String pattern, boolean exact, String require, List<String> methods, String addonId) {
    }

    public static final String MODE_OBSERVE = "observe";
    public static final String MODE_ENFORCE = "enforce";

    private final AddonRegistry registry;
    private final String mode;

    private volatile List<ApiRule> rules = List.of();

    public RbacService(AddonRegistry registry,
            @Value("${yingzao.rbac.mode:observe}") String mode) {
        this.registry = registry;
        this.mode = mode == null || mode.isBlank() ? MODE_OBSERVE : mode.trim().toLowerCase();
    }

    @PostConstruct
    void rebuild() {
        List<ApiRule> built = new ArrayList<>();
        for (AddonDescriptor d : registry.enabled()) {
            for (AddonDescriptor.Api api : d.apis() == null ? List.<AddonDescriptor.Api>of() : d.apis()) {
                if (api.pattern() == null || api.pattern().isBlank() || api.require() == null
                        || api.require().isBlank()) {
                    log.warn("addon {} has an api rule without pattern/require, skipped", d.id());
                    continue;
                }
                built.add(new ApiRule(api.pattern(), api.exactMatch(), api.require(),
                        api.methods() == null ? List.of() : api.methods(), d.id()));
            }
        }
        // prefix 按长度降序 → match 时首个前缀命中即最长匹配;exact 走 map 语义(线性扫,规则量级小)
        built.sort(Comparator.comparingInt((ApiRule r) -> r.pattern().length()).reversed());
        this.rules = List.copyOf(built);

        // 启动期校验(require 归属 + prefix 包含),只告警
        for (ApiRule rule : built) {
            AddonDescriptor owner = registry.get(rule.addonId());
            boolean looksLikeMenu = rule.require().startsWith("/");
            if (looksLikeMenu && owner.menus() != null
                    && owner.menus().stream().noneMatch(m -> rule.require().equals(m.path()))) {
                log.warn("rbac: addon {} api {} requires menu {} which is not declared in its menus",
                        rule.addonId(), rule.pattern(), rule.require());
            }
            if (!looksLikeMenu && owner.mounts() != null && !owner.mounts().isEmpty()
                    && owner.mounts().stream().noneMatch(m -> rule.require().equals(m.capability()))) {
                log.warn("rbac: addon {} api {} requires capability {} which is not declared in its mounts",
                        rule.addonId(), rule.pattern(), rule.require());
            }
        }
        for (int i = 0; i < built.size(); i++) {
            for (int j = i + 1; j < built.size(); j++) {
                ApiRule a = built.get(i);
                ApiRule b = built.get(j);
                if (!a.exact() && !b.exact()
                        && (a.pattern().startsWith(b.pattern()) || b.pattern().startsWith(a.pattern()))) {
                    log.warn("rbac: overlapping prefixes {} ({}), {} ({}) - longest wins",
                            a.pattern(), a.addonId(), b.pattern(), b.addonId());
                }
            }
        }
        log.info("rbac mode={} api rules: {} (addons: {})", mode, built.size(),
                built.stream().map(ApiRule::addonId).distinct().toList());
    }

    /** 请求 URI → require 身份;未命中返回 empty(按 mode 决定放行或拒)。 */
    public Optional<ApiRule> match(String uri) {
        for (ApiRule rule : rules) {
            if (rule.exact()) {
                if (rule.pattern().equals(uri)) {
                    return Optional.of(rule);
                }
            } else if (uri.startsWith(rule.pattern())) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public List<ApiRule> rules() {
        return rules;
    }

    /** 插件 URI 判定:/api/{启用插件id}/... ——enforce 的未声明拒绝只对插件 API 生效,
     * 内核端点(auth/roles/addons/notifications/events/users 等)自带守卫,豁免(设计 §2-4)。 */
    public boolean isPluginUri(String uri) {
        if (!uri.startsWith("/api/")) {
            return false;
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        String head = slash > 0 ? rest.substring(0, slash) : rest;
        return registry.isEnabled(head);
    }

    public String mode() {
        return mode;
    }

    public boolean enforce() {
        return MODE_ENFORCE.equals(mode);
    }
}
