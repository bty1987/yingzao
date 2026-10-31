package com.yingzao.core.audit;

import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.web.BizException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @AuditLog 切面单测(F4a,docs/15 R26~R27):成功=0/BizException=1/其他 Throwable=2
 * 且均原样重抛;SpEL objectId/summary;缺省 action=方法名/module=包末段/summary=脱敏参数。
 * 注解消费方(UserController 等)的记录契约由本类锚定。
 */
class AuditLogAspectTest extends KernelTestBase {

    record BodyCmd(String username, String password, int status) {
    }

    static class Target {
        @AuditLog(action = "create", module = "user", objectType = "user",
                objectId = "#id", summary = "'status=' + #body.status()")
        public void create(long id, BodyCmd body) {
        }

        @AuditLog(objectType = "user")
        public void defaults(long id, BodyCmd body) {
        }
    }

    private AuditRecorder recorder;
    private AuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        recorder = new AuditRecorder(jdbc, new DataSourceTransactionManager(dataSource));
        aspect = new AuditLogAspect(recorder);
    }

    private void invoke(String method, Object[] args, boolean succeed) throws Throwable {
        var m = Target.class.getMethod(method, long.class, BodyCmd.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(pjp.getArgs()).thenReturn(args);
        if (succeed) {
            when(pjp.proceed()).thenReturn(null);
        } else {
            when(pjp.proceed()).thenAnswer(inv -> {
                throw new BizException(120101, "dup");
            });
        }
        aspect.around(pjp, m.getAnnotation(AuditLog.class));
    }

    private Map<String, Object> onlyRow() {
        return jdbc.queryForMap("select * from yz_audit_log");
    }

    @Test
    void successRecordsSpelFieldsAndMaskedDetail() throws Throwable {
        invoke("create", new Object[]{7L, new BodyCmd("张三", "p@ss", 1)}, true);
        Map<String, Object> row = onlyRow();
        assertEquals(0, ((Number) row.get("result")).intValue());
        assertEquals("create", row.get("action"));
        assertEquals("user", row.get("module"));
        assertEquals("user", row.get("object_type"));
        assertEquals("7", row.get("object_id"));
        assertEquals("status=1", row.get("summary"));
        // 摘要走 SpEL 时,明细=脱敏参数 JSON(密码必须 ***)
        assertTrue(String.valueOf(row.get("detail")).contains("\"password\":\"***\""));
        assertTrue(((Number) row.get("run_time")).intValue() >= 0);
    }

    @Test
    void bizExceptionRecordsResultOneAndRethrows() {
        BizException ex = assertThrows(BizException.class, () ->
                invoke("create", new Object[]{7L, new BodyCmd("张三", "p@ss", 1)}, false));
        assertEquals(120101, ex.getCode());
        Map<String, Object> row = onlyRow();
        assertEquals(1, ((Number) row.get("result")).intValue());
        assertEquals(120101, ((Number) row.get("error_code")).intValue());
        assertEquals("dup", row.get("error_msg"));
    }

    @Test
    void defaultsFillActionModuleAndMaskedSummary() throws Throwable {
        var m = Target.class.getMethod("defaults", long.class, BodyCmd.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(pjp.getArgs()).thenReturn(new Object[]{9L, new BodyCmd("李四", "s3cret", 0)});
        when(pjp.proceed()).thenReturn(null);
        aspect.around(pjp, m.getAnnotation(AuditLog.class));
        Map<String, Object> row = onlyRow();
        assertEquals("defaults", row.get("action"));
        assertEquals("audit", row.get("module"));
        assertEquals("user", row.get("object_type"));
        assertEquals("", row.get("object_id"));
        // 缺省摘要=脱敏参数 JSON(而非 SpEL)
        assertTrue(String.valueOf(row.get("summary")).contains("\"password\":\"***\""));
    }

    @Test
    void systemErrorRecordedAsResultTwo() throws Throwable {
        var m = Target.class.getMethod("create", long.class, BodyCmd.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(pjp.getArgs()).thenReturn(new Object[]{7L, new BodyCmd("张三", "x", 1)});
        when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> aspect.around(pjp, m.getAnnotation(AuditLog.class)));
        Map<String, Object> row = onlyRow();
        assertEquals(2, ((Number) row.get("result")).intValue());
        assertEquals("boom", row.get("error_msg"));
    }

    @Test
    void aspectNeverFailsBusinessWhenRecordingBlowsUp() throws Throwable {
        // 记录动作自身异常全吞:表被删仍不影响主流程返回
        jdbc.update("alter table yz_audit_log rename to yz_audit_log_bak");
        try {
            invoke("create", new Object[]{7L, new BodyCmd("张三", "x", 1)}, true);
        } finally {
            jdbc.update("alter table yz_audit_log_bak rename to yz_audit_log");
        }
    }
}
