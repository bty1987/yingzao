package com.yingzao.core.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.yingzao.core.web.BizException;

/**
 * @AuditLog 切面:proceed 成功记 result=0;BizException 记 result=1 原样重抛;
 * 其他 Throwable 记 result=2 原样重抛。记录动作自身(含 SpEL/脱敏)任何异常都被
 * Recorder 吞掉,绝不影响主业务(失败恰恰是最需要留痕的时刻,REQUIRES_NEW 保证)。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditRecorder recorder;

    public AuditLogAspect(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            record(pjp, auditLog, AuditRecorder.RESULT_SUCCESS, null, null,
                    System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            record(pjp, auditLog, AuditRecorder.RESULT_FAIL, e.getCode(), e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        } catch (Throwable t) {
            record(pjp, auditLog, AuditRecorder.RESULT_ERROR, null,
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                    System.currentTimeMillis() - start);
            throw t;
        }
    }

    private void record(ProceedingJoinPoint pjp, AuditLog auditLog, int result,
            Integer errorCode, String errorMsg, long runTime) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            var method = signature.getMethod();
            String action = auditLog.action().isBlank() ? method.getName() : auditLog.action();
            String module = auditLog.module().isBlank()
                    ? method.getDeclaringClass().getPackageName()
                    : auditLog.module();
            module = module.substring(module.lastIndexOf('.') + 1);
            String objectId = auditLog.objectId().isBlank()
                    ? "" : nullSafe(recorder.evalSpel(method, pjp.getArgs(), auditLog.objectId()));
            String summary = auditLog.summary().isBlank()
                    ? recorder.sanitizeArgs(method, pjp.getArgs())
                    : recorder.evalSpel(method, pjp.getArgs(), auditLog.summary());
            recorder.operation(module, action, auditLog.objectType(), objectId,
                    summary == null ? "" : summary,
                    auditLog.summary().isBlank() ? null : recorder.sanitizeArgs(method, pjp.getArgs()),
                    result, errorCode, errorMsg, runTime);
        } catch (Throwable t) {
            log.error("[audit] aspect record failed (swallowed)", t);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
