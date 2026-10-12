package com.yingzao.core.context;

/**
 * 轻量请求上下文(内核平台设施,任何插件可用):当前登录人 userId/orgId 的
 * ThreadLocal 快照,由内核认证拦截器在请求入口绑定、afterCompletion 清理。
 * 数据权限 L0(组织硬边界)的配套件——插件业务查询从这里取 orgId,
 * 禁止从请求参数取(设计稿 03 §6)。
 */
public final class YzContext {

    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<Long> ORG = new ThreadLocal<>();

    private YzContext() {
    }

    public static void set(long userId, long orgId) {
        USER.set(userId);
        ORG.set(orgId);
    }

    public static long userId() {
        Long v = USER.get();
        if (v == null) {
            throw new IllegalStateException("YzContext not bound (outside authenticated request?)");
        }
        return v;
    }

    public static long orgId() {
        Long v = ORG.get();
        if (v == null) {
            throw new IllegalStateException("YzContext not bound (outside authenticated request?)");
        }
        return v;
    }

    public static void clear() {
        USER.remove();
        ORG.remove();
    }
}
