package com.yingzao.core.eventbus;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.eventbus.YzEventBus.BizEventNotFound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件总线单测(F4a 内核高险面,docs/15 R26~R27):出站箱发布/消费确认/指数退避/
 * 死信/requeue/exactly-once 消费登记/调度到期谓词。信息域红线(docs/rules/information.md)
 * 「老 messagequeuing 近半事件失败无重试悄然丢失」的回归网——本类失败 = 丢失红线失守。
 */
class YzEventBusTest extends KernelTestBase {

    record T1Event(String name) {
    }

    /** 恒成功消费者:收件箱可断言。 */
    static class OkConsumer implements YzEventConsumer {
        final Set<String> types;
        final List<YzEventRecord> inbox = new ArrayList<>();

        OkConsumer(String... types) {
            this.types = Set.of(types);
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void consume(YzEventRecord event) {
            inbox.add(event);
        }
    }

    /** 武装期恒失败消费者(重试/死信/exactly-once 场景用)。 */
    static class FlakyConsumer implements YzEventConsumer {
        final List<YzEventRecord> inbox = new ArrayList<>();
        boolean armed = true;

        @Override
        public Set<String> eventTypes() {
            return Set.of("T1Event", "FlakyEvent");
        }

        @Override
        public void consume(YzEventRecord event) {
            inbox.add(event);
            if (armed) {
                throw new IllegalStateException("flaky boom");
            }
        }
    }

    private OkConsumer ok;
    private YzEventBus bus;

    private YzEventBus newBus(YzEventConsumer... consumers) {
        return new YzEventBus(jdbc, List.of(consumers), 2L, 3600L, 5);
    }

    private Map<String, Object> task(long id) {
        return jdbc.queryForMap("select id, event_type, status, retry_count, max_retries, "
                + "next_retry_time, last_error from yz_event_task where id = ?", id);
    }

    private long seed(String type, String payload, int status, String nextRetryExpr, String claimedExpr) {
        jdbc.update("insert into yz_event_task(event_type, payload, status) values (?, ?, ?)", type, payload, status);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        if (nextRetryExpr != null) {
            jdbc.update("update yz_event_task set next_retry_time = " + nextRetryExpr + " where id = ?", id);
        }
        if (claimedExpr != null) {
            jdbc.update("update yz_event_task set claimed_at = " + claimedExpr + " where id = ?", id);
        }
        return id;
    }

    @Test
    void publishWithoutConsumerArchives() {
        bus = newBus();
        bus.publish(new T1Event("a"), "k1");
        Map<String, Object> row = jdbc.queryForMap("select status from yz_event_task");
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) row.get("status")).intValue());
    }

    @Test
    void publishDeliversToMatchingConsumerAndRegisters() {
        ok = new OkConsumer("T1Event");
        bus = newBus(ok);
        bus.publish(new T1Event("营造"), "k1");
        assertEquals(1, ok.inbox.size());
        YzEventRecord rec = ok.inbox.get(0);
        assertTrue(rec.taskId() > 0);
        assertEquals("T1Event", rec.eventType());
        assertEquals("k1", rec.eventKey());
        assertEquals("营造", rec.payloadAs(T1Event.class).name());
        Integer done = jdbc.queryForObject(
                "select count(*) from yz_event_consumed where task_id = ? and consumer = 'OkConsumer'",
                Integer.class, rec.taskId());
        assertEquals(1, done);
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) task(rec.taskId()).get("status")).intValue());
    }

    @Test
    void payloadUnknownFieldTolerated() {
        ok = new OkConsumer("T1Event");
        bus = newBus(ok);
        // 手工落一条带未知字段的载荷(契约加字段向后兼容)
        jdbc.update("insert into yz_event_task(event_type, payload, event_key) values (?, ?, ?)",
                "T1Event", "{\"name\":\"a\",\"extra\":123}", "k");
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        bus.dispatchTask(id);
        assertEquals(1, ok.inbox.size());
        assertEquals("a", ok.inbox.get(0).payloadAs(T1Event.class).name());
    }

    @Test
    void consumerFailureRetriesWithBackoff() {
        FlakyConsumer flaky = new FlakyConsumer();
        bus = new YzEventBus(jdbc, List.of(flaky), 2L, 3600L, 5);
        bus.publish(new T1Event("a"), "k1");
        long id = latestTaskId();
        Map<String, Object> row = task(id);
        assertEquals(YzEventBus.STATUS_RETRYING, ((Number) row.get("status")).intValue());
        assertEquals(1, ((Number) row.get("retry_count")).intValue());
        assertTrue(String.valueOf(row.get("last_error")).contains("FlakyConsumer"));
        // 退避公式:backoff * 2^next = 2 * 2^1 = 4s(实测窗口 2.5s~8s)
        Timestamp due = (Timestamp) row.get("next_retry_time");
        long delta = due.getTime() - System.currentTimeMillis();
        assertTrue(delta > 2500 && delta < 8000, "backoff should be ~4s, got " + delta + "ms");
    }

    private long latestTaskId() { // 单用例内最新一条任务
        return jdbc.queryForObject("select max(id) from yz_event_task", Long.class);
    }

    @Test
    void exhaustToDeadLetter() {
        FlakyConsumer flaky = new FlakyConsumer();
        // maxRetries=1:首败即死信(next=1 >= max=1)
        bus = new YzEventBus(jdbc, List.of(flaky), 2L, 3600L, 1);
        bus.publish(new T1Event("a"), "k1");
        long id = latestTaskId();
        Map<String, Object> row = task(id);
        assertEquals(YzEventBus.STATUS_DEAD, ((Number) row.get("status")).intValue());
        assertEquals(1, ((Number) row.get("retry_count")).intValue());
        assertTrue(String.valueOf(row.get("last_error")).contains("flaky boom"));
        assertNull(row.get("claimed_at"));
    }

    @Test
    void requeueResetsAndRedelivers() {
        FlakyConsumer flaky = new FlakyConsumer();
        bus = new YzEventBus(jdbc, List.of(flaky), 2L, 3600L, 1);
        bus.publish(new T1Event("a"), "k1");
        long id = latestTaskId();
        assertEquals(YzEventBus.STATUS_DEAD, ((Number) task(id).get("status")).intValue());
        flaky.armed = false;
        bus.requeue(id);
        // requeue 立即触发一轮投递(无事务=同步);收件箱=发布失败 1 次 + 补跑成功 1 次
        Map<String, Object> row = task(id);
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) row.get("status")).intValue());
        assertEquals(0, ((Number) row.get("retry_count")).intValue());
        assertEquals(2, flaky.inbox.size());
        assertThrows(BizEventNotFound.class, () -> bus.requeue(999999));
    }

    @Test
    void exactlyOnceSkipsAlreadySucceededConsumers() {
        ok = new OkConsumer("T1Event");
        FlakyConsumer flaky = new FlakyConsumer();
        bus = newBus(ok, flaky);
        bus.publish(new T1Event("a"), "k1");
        long id = latestTaskId();
        // 第一轮:ok 成功登记,flaky 失败 → 整条重试
        assertEquals(YzEventBus.STATUS_RETRYING, ((Number) task(id).get("status")).intValue());
        assertEquals(1, ok.inbox.size());
        Integer okDone = jdbc.queryForObject(
                "select count(*) from yz_event_consumed where task_id = ? and consumer = 'OkConsumer'",
                Integer.class, id);
        assertEquals(1, okDone);
        // 第二轮(直接投递,绕过 next_retry_time):ok 不重放,flaky 补跑成功
        flaky.armed = false;
        bus.dispatchTask(id);
        assertEquals(1, ok.inbox.size(), "已成功消费者不得重放");
        assertEquals(2, flaky.inbox.size());
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) task(id).get("status")).intValue());
        Integer both = jdbc.queryForObject(
                "select count(*) from yz_event_consumed where task_id = ?", Integer.class, id);
        assertEquals(2, both);
    }

    @Test
    void dispatchDuePicksOnlyDueTasks() {
        ok = new OkConsumer("DueEvent");
        bus = newBus(ok);
        long a = seed("DueEvent", "{}", YzEventBus.STATUS_PENDING, null, null);              // 到期
        long b = seed("DueEvent", "{}", YzEventBus.STATUS_RETRYING,
                "dateadd(HOUR, 1, now())", null);                                            // 未到期
        long c = seed("DueEvent", "{}", YzEventBus.STATUS_PENDING, null, "now()");           // 刚认领=在管
        long d = seed("DueEvent", "{}", YzEventBus.STATUS_PENDING, null,
                "dateadd(MINUTE, -6, now())");                                               // 崩溃回收
        long e = seed("DueEvent", "{}", YzEventBus.STATUS_CONSUMED, null, null);             // 已消费
        bus.dispatchDue(10);
        assertEquals(2, ok.inbox.size(), "只应投递到期(a)与崩溃回收(d)两条");
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) task(a).get("status")).intValue());
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) task(d).get("status")).intValue());
        assertEquals(YzEventBus.STATUS_RETRYING, ((Number) task(b).get("status")).intValue());
        assertEquals(YzEventBus.STATUS_PENDING, ((Number) task(c).get("status")).intValue());
        assertEquals(YzEventBus.STATUS_CONSUMED, ((Number) task(e).get("status")).intValue());
    }

    @Test
    void dispatchTaskSkipsNonClaimableTask() {
        ok = new OkConsumer("T1Event");
        bus = newBus(ok);
        bus.publish(new T1Event("a"), "k1");
        long id = latestTaskId();
        int before = ok.inbox.size();
        bus.dispatchTask(id); // 已消费 → 认领失败 → 零副作用
        assertEquals(before, ok.inbox.size());
    }

    @Test
    void backoffCapApplies() {
        FlakyConsumer flaky = new FlakyConsumer();
        // cap=4s:把 retry_count 预置 9 → next=10 → 2*2^10=2048s 封顶 4s(max_retries 同步给 20 防"未到 cap 先死信")
        bus = new YzEventBus(jdbc, List.of(flaky), 2L, 4L, 20);
        jdbc.update("insert into yz_event_task(event_type, payload, status, retry_count, max_retries) "
                + "values (?, ?, 2, 9, 20)", "FlakyEvent", "{}");
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        bus.dispatchTask(id);
        Timestamp due = (Timestamp) task(id).get("next_retry_time");
        long delta = due.getTime() - System.currentTimeMillis();
        assertTrue(delta > 2500 && delta < 6500, "capped backoff should be ~4s, got " + delta + "ms");
    }

    @Test
    void pageAndStatsShape() {
        bus = newBus();
        bus.publish(new T1Event("a"), "k1");
        bus.publish(new T1Event("b"), "k2");
        Map<String, Object> page = bus.page(null, null, 1, 10);
        assertEquals(2L, ((Number) page.get("total")).longValue());
        assertTrue(((List<?>) page.get("rows")).size() == 2);
        Map<String, Object> filtered = bus.page(YzEventBus.STATUS_CONSUMED, "T1Event", 1, 10);
        assertEquals(2L, ((Number) filtered.get("total")).longValue());
        Map<String, Object> stats = bus.stats();
        assertEquals(2L, ((Number) stats.get("status1")).longValue());
        assertNotEquals(0, stats.size());
    }
}
