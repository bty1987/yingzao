package com.yingzao.core.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解(F3,docs/15 R24~R25):标注在 Controller/Service 方法上,由
 * {@link AuditLogAspect} 统一记录一条操作日志(yz_audit_log category=0)。
 *
 * <p>与老系统 AccessInterceptor 全请求捕获的差异:注解 opt-in——注解到哪记到哪,
 * 查询/读端点不注即零噪声;老 logFlag 三档配置面不迁(docs/rules/audit.md §0)。
 *
 * <p>记录纪律:REQUIRES_NEW 独立事务落库(业务回滚审计仍在)+切面吞写库异常绝不
 * 影响主业务;BizException→result=1(业务失败),其他 Throwable→result=2(系统异常),
 * 两者均原样重抛。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditLog {

    /** 动作名;缺省=方法名。 */
    String action() default "";

    /** 模块;缺省=类所在包最后一段。 */
    String module() default "";

    /** 对象类型(如 decoration_order/user);缺省不记。 */
    String objectType() default "";

    /** 对象主键 SpEL(方法参数可见,如 "#id"/"#cmd.orderId()");空=不记。 */
    String objectId() default "";

    /** 摘要 SpEL(方法参数可见);空=脱敏参数 JSON。 */
    String summary() default "";
}
