package com.yingzao.core.addon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * 启动时扫描 classpath 上的全部 addon.yml,向框架与前端暴露已启用插件清单。
 * 仅读取声明,不做类加载隔离——插件代码经编译期依赖 + ConditionalOnAddon 装配。
 */
public class AddonRegistry {

    private static final Logger log = LoggerFactory.getLogger(AddonRegistry.class);

    private final Map<String, AddonDescriptor> descriptors;

    public AddonRegistry(List<String> enabledIds) {
        this.descriptors = scan().stream()
                .filter(d -> enabledIds.contains(d.id()))
                .collect(Collectors.toMap(AddonDescriptor::id, Function.identity()));
        log.info("yingzao addons enabled: {}", descriptors.keySet());
    }

    private List<AddonDescriptor> scan() {
        var resolver = new PathMatchingResourcePatternResolver();
        List<AddonDescriptor> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:addon.yml")) {
                try (var in = resource.getInputStream()) {
                    Map<String, Object> raw = new Yaml().load(in);
                    found.add(parse(raw));
                } catch (Exception e) {
                    log.warn("skip invalid addon.yml at {}: {}", resource, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("addon.yml scan failed: {}", e.getMessage());
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private AddonDescriptor parse(Map<String, Object> raw) {
        Map<String, Object> a = (Map<String, Object>) raw.get("addon");
        List<Map<String, Object>> mounts = (List<Map<String, Object>>) a.getOrDefault("mounts", List.of());
        List<AddonDescriptor.Mount> parsedMounts = mounts.stream()
                .map(m -> new AddonDescriptor.Mount((String) m.get("slot"), (String) m.get("component"),
                        (String) m.get("capability")))
                .toList();
        return new AddonDescriptor(
                (String) a.get("id"),
                (String) a.get("name"),
                (String) a.getOrDefault("version", "0.0.0"),
                a.get("error-code-segment") == null ? 0 : (Integer) a.get("error-code-segment"),
                parsedMounts,
                (Map<String, Object>) a.getOrDefault("dicts", Map.of()));
    }

    public List<AddonDescriptor> enabled() {
        return List.copyOf(descriptors.values());
    }

    public boolean isEnabled(String addonId) {
        return descriptors.containsKey(addonId);
    }

    public AddonDescriptor get(String addonId) {
        return descriptors.get(addonId);
    }
}
