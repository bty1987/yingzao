package com.yingzao.core.addon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * 插件约定接入(EnvironmentPostProcessor):新插件接入除 yingzao.addons.enabled 一行
 * 激活开关外零登记——i18n basename(i18n/&lt;id&gt;)与迁移位置(classpath:db/migration/&lt;id&gt;)
 * 按约定从 classpath 上的 addon.yml 推导,在配置绑定前追加进 spring.messages.basename 与
 * spring.flyway.locations(application.yml 只保留内核项)。
 *
 * <p>收口「新 addon 接入三处静态登记」的失败模式(第 49 次踩坑:enabled/messages basename/
 * flyway locations 漏一即静默失效——插件不可见/错误码全部回落 110000/迁移静默不执行):
 * 后两处由「每插件手写」变「结构性不可能漏」。enabled 漏仍会静默(菜单不可见),保留为
 * 部署侧显式激活决策(企业装插件 = 放 jar + 开一行)。
 *
 * <p>时机:Order=LOWEST,跑在 application.yml 加载(ConfigData)之后、@ConfigurationProperties
 * 绑定之前;追加以 addFirst 全量合并值形式注入(读到的现值含环境变量覆盖,合并后不丢失);
 * 日志经 DeferredLogFactory,logging 初始化后回放。
 */
public class AddonConventionPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "yingzaoAddonConventions";

    private final org.apache.commons.logging.Log log;

    public AddonConventionPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(AddonConventionPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> enabled = Arrays.stream(
                        environment.getProperty("yingzao.addons.enabled", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (enabled.isEmpty()) {
            return;
        }
        List<String> present = scanIds().stream().filter(enabled::contains).toList();
        if (present.isEmpty()) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merge(environment, merged, "spring.messages.basename", present.stream()
                .filter(id -> resourceExists("classpath*:i18n/" + id + ".properties"))
                .map(id -> "i18n/" + id).toList());
        merge(environment, merged, "spring.flyway.locations", present.stream()
                .filter(id -> resourceExists("classpath*:db/migration/" + id + "/*.sql"))
                .map(id -> "classpath:db/migration/" + id).toList());
        if (merged.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, merged));
        log.info("addon conventions: " + present.size() + " addon(s) merged into messages/flyway properties");
    }

    /** 追加合并:读现值(含 yml/环境变量覆盖)→ 去重追加约定项(调用侧已按资源存在过滤)。 */
    private void merge(ConfigurableEnvironment environment, Map<String, Object> out,
            String key, List<String> conventions) {
        if (conventions.isEmpty()) {
            return;
        }
        List<String> merged = new ArrayList<>(Arrays.stream(
                        environment.getProperty(key, "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        boolean added = false;
        for (String convention : conventions) {
            if (!merged.contains(convention)) {
                merged.add(convention);
                added = true;
            }
        }
        if (added) {
            out.put(key, String.join(",", merged));
            log.info(key + " += " + conventions);
        }
    }

    /** classpath 上的 addon.yml 清单(与 AddonRegistry 同机制;解析失败跳过,AddonRegistry 会再报)。 */
    private List<String> scanIds() {
        List<String> ids = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:addon.yml")) {
                try (var in = resource.getInputStream()) {
                    Object raw = new Yaml().load(in);
                    if (raw instanceof Map<?, ?> root && root.get("addon") instanceof Map<?, ?> addon
                            && addon.get("id") instanceof String id) {
                        ids.add(id);
                    }
                } catch (Exception e) {
                    log.warn("skip invalid addon.yml at " + resource + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("addon.yml scan failed: " + e.getMessage());
        }
        return ids;
    }

    private boolean resourceExists(String pattern) {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(pattern);
            return found.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
