package com.yingzao.core.audit;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.web.BizException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计落库器单测(F4a,docs/15 R26~R27):树级脱敏(record Cmd 展开——第 115 次
 * 真缺陷「密码明文进 detail」的常驻回归锚)/SpEL 参数名绑定(-parameters 军规)/
 * 三类写入(操作/登录/踢出)与 safe 全吞语义。
 */
class AuditRecorderTest extends KernelTestBase {

    record UserBody(String username, String password, String phone, String nickname) {
    }

    static class Stubs {
        public void create(long id, jakarta.servlet.http.HttpServletRequest request, UserBody body) {
        }

        public void withFile(File file, UserBody body) {
        }

        public void nothing() {
        }
    }

    private AuditRecorder recorder;

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditRecorder newRecorder() {
        return new AuditRecorder(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void classifyBizVsSystem() {
        assertEquals(AuditRecorder.RESULT_FAIL, AuditRecorder.classify(new BizException(120101, "x")));
        assertEquals(AuditRecorder.RESULT_ERROR, AuditRecorder.classify(new IllegalStateException("x")));
    }

    @Test
    void sanitizeArgsExpandsRecordAndMasks() throws Exception {
        recorder = newRecorder();
        var m = Stubs.class.getMethod("create", long.class,
                jakarta.servlet.http.HttpServletRequest.class, UserBody.class);
        String json = recorder.sanitizeArgs(m, new Object[]{
                7L, new MockHttpServletRequest(),
                new UserBody("张三", "p@ss", "13812345678", "小明")});
        // record 参数树级展开;精确键与模糊键(phone)都替换;无关键原样
        assertTrue(json.contains("\"id\":7"));
        assertTrue(json.contains("\"password\":\"***\""));
        assertTrue(json.contains("\"phone\":\"***\""));
        assertTrue(json.contains("\"nickname\":\"小明\""));
        assertFalse(json.contains("p@ss"));
        assertFalse(json.contains("13812345678"));
    }

    @Test
    void sanitizeArgsSkipsFrameworkAndIoParams() throws Exception {
        recorder = newRecorder();
        var m = Stubs.class.getMethod("withFile", File.class, UserBody.class);
        String json = recorder.sanitizeArgs(m, new Object[]{
                new File("/tmp/a"), new UserBody("u", "pwd", null, null)});
        assertFalse(json.contains("tmp"));
        assertTrue(json.contains("\"body\":{"));
    }

    @Test
    void sanitizeArgsTruncatesOverlongJson() throws Exception {
        recorder = newRecorder();
        var m = Stubs.class.getMethod("withFile", File.class, UserBody.class);
        UserBody big = new UserBody("u".repeat(3000), null, null, null);
        String json = recorder.sanitizeArgs(m, new Object[]{null, big});
        assertTrue(json.length() <= 2000 + "...[truncated]".length());
        assertTrue(json.endsWith("...[truncated]"));
    }

    @Test
    void sanitizeArgsEmptyReturnsNull() throws Exception {
        recorder = newRecorder();
        var m = Stubs.class.getMethod("nothing");
        assertNull(recorder.sanitizeArgs(m, new Object[0]));
    }

    @Test
    void evalSpelResolvesParameterNames() throws Exception {
        recorder = newRecorder();
        var m = Stubs.class.getMethod("create", long.class,
                jakarta.servlet.http.HttpServletRequest.class, UserBody.class);
        Object[] args = {7L, null, new UserBody("张三", "x", null, null)};
        assertEquals("7", recorder.evalSpel(m, args, "#id"));
        assertEquals("张三", recorder.evalSpel(m, args, "#body.username()"));
        // SpEL 异常回落 null(切面回落缺省,绝不因审计炸业务)
        assertNull(recorder.evalSpel(m, args, "#body.blowUp()"));
    }

    @Test
    void operationInsertsWithRequestContext() {
        recorder = newRecorder();
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) "
                + "values (2, 1, 'admin', 'x', '管理员')");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        req.setAttribute("yz.userId", 2L);
        req.setAttribute("yz.orgId", 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        recorder.operation("user", "create", "user", "7", "新增用户", "{\"a\":1}",
                AuditRecorder.RESULT_SUCCESS, null, null, 123);
        Map<String, Object> row = jdbc.queryForMap("select * from yz_audit_log");
        assertEquals(0, ((Number) row.get("category")).intValue());
        assertEquals(1L, ((Number) row.get("org_id")).longValue());
        assertEquals(2L, ((Number) row.get("user_id")).longValue());
        assertEquals("管理员", row.get("user_name"));
        assertEquals("203.0.113.9", row.get("ip"));
        assertEquals(123, ((Number) row.get("run_time")).intValue());
        assertEquals("create", row.get("action"));
    }

    @Test
    void loginRowsCoverSuccessFailureAndUnknownUser() {
        recorder = newRecorder();
        recorder.login(1L, "admin", 2L, false, 110001, "密码错误");
        recorder.login(null, "ghost", null, false, 110001, "用户不存在");
        recorder.login(1L, "admin", 2L, true, null, null);
        Integer rows = jdbc.queryForObject("select count(*) from yz_audit_log", Integer.class);
        assertEquals(3, rows);
        Map<String, Object> failed = jdbc.queryForMap(
                "select * from yz_audit_log where user_name = 'admin' and result = 1");
        assertEquals(1, ((Number) failed.get("category")).intValue());
        assertEquals(110001, ((Number) failed.get("error_code")).intValue());
        assertEquals("密码错误", failed.get("error_msg"));
        Map<String, Object> ghost = jdbc.queryForMap(
                "select * from yz_audit_log where user_name = 'ghost'");
        assertEquals(0L, ((Number) ghost.get("org_id")).longValue());
        assertEquals(0L, ((Number) ghost.get("user_id")).longValue());
        Integer successCodeNull = jdbc.queryForObject(
                "select count(*) from yz_audit_log where result = 0 and error_code is null", Integer.class);
        assertEquals(1, successCodeNull);
    }

    @Test
    void evictRowRecordsKickout() {
        recorder = newRecorder();
        recorder.evict(1, 5, "被踢人", "disable");
        Map<String, Object> row = jdbc.queryForMap("select * from yz_audit_log");
        assertEquals(1, ((Number) row.get("category")).intValue());
        assertEquals("evict", row.get("action"));
        assertEquals("5", row.get("object_id"));
        assertEquals("disable", row.get("summary"));
        assertEquals(5L, ((Number) row.get("user_id")).longValue());
    }

    @Test
    void writeFailureSwallowedNeverBreaksBusiness() {
        recorder = newRecorder();
        jdbc.update("alter table yz_audit_log rename to yz_audit_log_bak");
        try {
            recorder.operation("user", "create", "user", "7", "s", null,
                    AuditRecorder.RESULT_SUCCESS, null, null, 1);
        } finally {
            jdbc.update("alter table yz_audit_log_bak rename to yz_audit_log");
        }
        // 全吞:无异常抛出即通过
    }
}
