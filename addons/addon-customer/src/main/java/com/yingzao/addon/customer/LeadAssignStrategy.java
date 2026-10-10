package com.yingzao.addon.customer;

import com.yingzao.core.web.BizException;

/**
 * 客源分配策略(addon-customer 内部扩展点):按 mode 注册到策略表,新增策略 = 新增 bean,分配入口零改动。
 * 老系统四分支(user/dept/region/auto)的行业化收敛:org 单层下 v1 先落 manual/auto。
 */
public interface LeadAssignStrategy {

    /** 策略模式标识,分配接口按此路由(如 manual/auto) */
    String mode();

    /**
     * 解析分配责任人。
     *
     * @return 责任人用户 id(yz_user.id)
     * @throws BizException 无可分配目标(码段 210000 内)
     */
    long resolveAssignee(AssignContext context);

    record AssignContext(long orgId, long leadId, Long assigneeId) {
    }
}
