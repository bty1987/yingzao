package com.yingzao.core.plan;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingzao.core.KernelTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划任务内核单测(F4a 内核高险面,docs/15 R26~R27):提交即执行/到期谓词
 * (runAt 未来不抢跑)/认领超时回收/指数退避/死信/取消与重跑的管理语义。
 * member-expire-clear、audit-log-purge、target-pk-archive 三条计划链都跑在本设施上。
 */
class PlanServiceTest extends KernelTestBase {

    static class EchoHandler implements YzPlanHandler {
        final List<String> calls = new java.util.ArrayList<>();
        JsonNode lastParams;

        @Override
        public String handler() {
            return "test-echo";
        }

        @Override
        public PlanResult execute(long orgId, long planId, JsonNode params) {
            lastParams = params;
            calls.add(orgId + ":" + planId);
            return PlanResult.of("done:" + orgId);
        }
    }

    static class BoomHandler implements YzPlanHandler {
        int calls;

        @Override
        public String handler() {
            return "test-boom";
        }

        @Override
        public PlanResult execute(long orgId, long planId, JsonNode params) {
            calls++;
            throw new IllegalStateException("boom");
        }
    }

    private EchoHandler echo;
    private PlanService svc;

    private PlanService newService(YzPlanHandler... handlers) {
        return new PlanService(jdbc, STUB_MESSAGES, List.of(handlers), 1L, 60L);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select id, handler, status, retry_count, max_retries, "
                + "next_run_time, claimed_at, result, last_error from yz_plan where id = ?", id);
    }

    private static int status(Map<String, Object> row) {
        return ((Number) row.get("status")).intValue();
    }

    @Test
    void submitRunsImmediatelyWithoutTransaction() {
        echo = new EchoHandler();
        svc = newService(echo);
        long id = svc.submit(1, "test-echo", "k1", Map.of("k", "营造"), null, null, 9L);
        Map<String, Object> r = row(id);
        assertEquals(PlanService.STATUS_DONE, status(r));
        assertEquals("done:1", r.get("result"));
        assertEquals(1, echo.calls.size());
        assertEquals("营造", echo.lastParams.get("k").asText());
    }

    @Test
    void submitBlankBizKeyStoredAsNull() {
        svc = newService(new EchoHandler());
        svc.submit(1, "test-echo", "   ", null, null, null, null);
        Integer nullKeys = jdbc.queryForObject(
                "select count(*) from yz_plan where biz_key is null", Integer.class);
        assertEquals(1, nullKeys);
    }

    @Test
    void submitGuards() {
        svc = newService(new EchoHandler());
        expectBiz(PlanService.CODE_NO_HANDLER, () -> svc.submit(1, "nope", null, null, null, null, null));
        expectBiz(PlanService.CODE_PARAM, () -> svc.submit(1, " ", null, null, null, null, null));
        expectBiz(PlanService.CODE_PARAM, () -> svc.submit(1, "test-echo", null, null, 0, null, null));
    }

    @Test
    void runAtFutureStaysPending() {
        svc = newService(new EchoHandler());
        long id = svc.submit(1, "test-echo", "k", null, null, LocalDateTime.now().plusHours(2), 9L);
        // 提交后立即尝试执行:到期谓词排除未来任务 → 0 待执行
        assertEquals(PlanService.STATUS_PENDING, status(row(id)));
        svc.dispatchDue(10);
        assertEquals(PlanService.STATUS_PENDING, status(row(id)), "调度器不得抢跑未来任务");
        svc.dispatchPlan(id);
        assertEquals(PlanService.STATUS_PENDING, status(row(id)), "单条认领同样被到期谓词拒绝");
    }

    @Test
    void runAtPastRuns() {
        svc = newService(new EchoHandler());
        long id = svc.submit(1, "test-echo", "k", null, null, LocalDateTime.now().minusMinutes(5), 9L);
        assertEquals(PlanService.STATUS_DONE, status(row(id)));
    }

    @Test
    void paramsNullPassesNullToHandler() {
        echo = new EchoHandler();
        svc = newService(echo);
        svc.submit(1, "test-echo", null, null, null, null, null);
        assertNull(echo.lastParams);
    }

    @Test
    void cancelSemantics() {
        svc = newService(new EchoHandler());
        long future = svc.submit(1, "test-echo", null, null, null, LocalDateTime.now().plusHours(1), null);
        svc.cancel(future);
        assertEquals(PlanService.STATUS_CANCELLED, status(row(future)));
        expectBiz(PlanService.CODE_PLAN_STATE, () -> svc.cancel(future));
        expectBiz(PlanService.CODE_PLAN_NOT_FOUND, () -> svc.cancel(999999));
        // 已成功不可撤
        long done = svc.submit(1, "test-echo", null, null, null, null, null);
        expectBiz(PlanService.CODE_PLAN_STATE, () -> svc.cancel(done));
    }

    @Test
    void failureRetriesWithBackoffThenDead() {
        BoomHandler boom = new BoomHandler();
        svc = newService(boom);
        long id = svc.submit(1, "test-boom", null, null, 2, null, null);
        // 第 1 次失败:next=1 < 2 → 重试中,退避 = backoff * 2^0 = 1s
        Map<String, Object> r = row(id);
        assertEquals(PlanService.STATUS_RETRYING, status(r));
        assertEquals(1, ((Number) r.get("retry_count")).intValue());
        assertTrue(String.valueOf(r.get("last_error")).contains("test-boom: boom"));
        Timestamp due = (Timestamp) r.get("next_run_time");
        long delta = due.getTime() - System.currentTimeMillis();
        assertTrue(delta > 300 && delta < 3000, "backoff should be ~1s, got " + delta + "ms");
        // 强制到期 → 第 2 次失败:next=2 >= 2 → 死信
        jdbc.update("update yz_plan set next_run_time = null where id = ?", id);
        svc.dispatchDue(10);
        r = row(id);
        assertEquals(PlanService.STATUS_DEAD, status(r));
        assertEquals(2, ((Number) r.get("retry_count")).intValue());
        assertNull(r.get("claimed_at"));
        assertEquals(2, boom.calls);
    }

    @Test
    void retryResetsDeadAndReruns() {
        BoomHandler boom = new BoomHandler();
        svc = newService(boom);
        // maxRetries=2:提交失败一次 → 强制到期再失败 → 死信
        long id = svc.submit(1, "test-boom", null, null, 2, null, null);
        jdbc.update("update yz_plan set next_run_time = null where id = ?", id);
        svc.dispatchDue(10);
        assertEquals(PlanService.STATUS_DEAD, status(row(id)));
        svc.retry(id);
        // 重跑 = 重置后立即执行,再次失败 → 回到重试中且计数从 0 重新计(next=1 < 2)
        Map<String, Object> r = row(id);
        assertEquals(PlanService.STATUS_RETRYING, status(r));
        assertEquals(1, ((Number) r.get("retry_count")).intValue());
        // 重试中(2)同样允许 retry(死信/重试中任务重跑语义)——再次重置重跑
        svc.retry(id);
        assertEquals(PlanService.STATUS_RETRYING, status(row(id)));
        assertEquals(4, boom.calls, "四次执行:提交/到期补刀/两次 retry 重放");
        expectBiz(PlanService.CODE_PLAN_NOT_FOUND, () -> svc.retry(999999));
    }

    @Test
    void dispatchDueClaimGuard() {
        echo = new EchoHandler();
        svc = newService(echo);
        long id = svc.submit(1, "test-echo", null, null, null, LocalDateTime.now().plusHours(1), null);
        // 先把到期时刻拉回过去(只测认领闸),再压认领时刻
        jdbc.update("update yz_plan set next_run_time = dateadd(MINUTE, -5, now()) where id = ?", id);
        jdbc.update("update yz_plan set claimed_at = now() where id = ?", id);
        svc.dispatchDue(10);
        assertEquals(0, echo.calls.size(), "刚认领(30 分钟窗口内)的任务不得被并发抢跑");
        jdbc.update("update yz_plan set claimed_at = dateadd(MINUTE, -31, now()) where id = ?", id);
        svc.dispatchDue(10);
        assertEquals(1, echo.calls.size(), "认领超 30 分钟=投递者崩溃 → 回收执行");
        assertEquals(PlanService.STATUS_DONE, status(row(id)));
    }

    @Test
    void statsAndPageShape() {
        svc = newService(new EchoHandler());
        svc.submit(1, "test-echo", "a", null, null, LocalDateTime.now().plusHours(1), null);
        svc.submit(1, "test-echo", "b", null, null, null, null);
        Map<String, Object> stats = svc.stats();
        assertEquals(1L, ((Number) stats.get("status0")).longValue());
        assertEquals(1L, ((Number) stats.get("status1")).longValue());
        assertEquals(0L, ((Number) stats.get("status3")).longValue());
        Map<String, Object> page = svc.page(PlanService.STATUS_DONE, "test-echo", 1L, 1, 10);
        assertEquals(1L, ((Number) page.get("total")).longValue());
        Map<String, Object> all = svc.page(null, null, null, 1, 10);
        assertEquals(2L, ((Number) all.get("total")).longValue());
        // get 不存在
        expectBiz(PlanService.CODE_PLAN_NOT_FOUND, () -> svc.get(999999));
    }
}
