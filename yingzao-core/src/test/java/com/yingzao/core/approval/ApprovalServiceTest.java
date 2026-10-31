package com.yingzao.core.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.approval.ApprovalService.SubmitCmd;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轻量审批状态机单测(F4a 内核高险面,docs/15 R26~R27):0 审批中 → 1 通过/2 驳回/3 撤回,
 * 转交只换 assignee 不动状态;守卫族(非处理人/非发起人/终态再操作/跨组织处理人);
 * 流水 append-only 与三类事件(eventKey 幂等锚)。库存审核/退款/装修过渡态全走本状态机。
 */
class ApprovalServiceTest extends KernelTestBase {

    static class RecordingConsumer implements YzEventConsumer {
        final List<YzEventRecord> received = new ArrayList<>();

        @Override
        public Set<String> eventTypes() {
            return Set.of("ApprovalSubmittedEvent", "ApprovalFinishedEvent", "ApprovalTransferredEvent");
        }

        @Override
        public void consume(YzEventRecord event) {
            received.add(event);
        }
    }

    private RecordingConsumer events;
    private ApprovalService svc;

    @BeforeEach
    void seedUsers() {
        events = new RecordingConsumer();
        YzEventBus bus = new YzEventBus(jdbc, List.of(events), 2L, 3600L, 5);
        svc = new ApprovalService(jdbc, STUB_MESSAGES, bus);
        jdbc.update("insert into yz_user(id, org_id, username, password_sha256, display_name) values "
                + "(1, 1, 'u1', 'x', '发起人'), (2, 1, 'u2', 'x', '审批人'), "
                + "(3, 1, 'u3', 'x', '同事'), (4, 2, 'u4', 'x', '外组织')");
    }

    private SubmitCmd cmd(Long assigneeId) {
        return new SubmitCmd(1, 1, "领料审核", "inventory", "requisition", "42", assigneeId);
    }

    private long submit() {
        return svc.submit(cmd(2L));
    }

    /** 取本审批单的办结事件(按事件类型过滤——SubmittedEvent 同名子集会误匹配,跳过它)。 */
    private ApprovalFinishedEvent finishedEvent(long id) {
        return events.received.stream()
                .filter(e -> "ApprovalFinishedEvent".equals(e.eventType()))
                .map(e -> e.payloadAs(ApprovalFinishedEvent.class))
                .filter(e -> e.approvalId() == id)
                .findFirst().orElseThrow();
    }

    @Test
    void submitGuards() {
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_REQUEST,
                () -> svc.submit(new SubmitCmd(1, 1, " ", "a", "b", "c", 2L)));
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_REQUEST,
                () -> svc.submit(new SubmitCmd(1, 1, "t", " ", "b", "c", 2L)));
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_REQUEST,
                () -> svc.submit(new SubmitCmd(1, 1, "t", "a", "b", "c", null)));
        // 处理人必须同组织
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_ASSIGNEE, () -> svc.submit(cmd(4L)));
    }

    @Test
    void submitSuccessCreatesRowRecordAndEvent() {
        long id = svc.submit(
                new SubmitCmd(1, 1, "退货审批", "sales", "refund", "7", 2L, "{\"amount\":300}", "[1,2]"));
        Map<String, Object> row = jdbc.queryForMap("select status, title, form_data, attachments "
                + "from yz_approval where id = ?", id);
        assertEquals(0, ((Number) row.get("status")).intValue());
        assertEquals("{\"amount\":300}", row.get("form_data"));
        assertEquals("[1,2]", row.get("attachments"));
        Integer records = jdbc.queryForObject(
                "select count(*) from yz_approval_record where approval_id = ? and action = 1",
                Integer.class, id);
        assertEquals(1, records);
        // 事件已发布(无事务=提交后立即投递),eventKey=幂等锚
        String eventKey = jdbc.queryForObject(
                "select event_key from yz_event_task where event_type = 'ApprovalSubmittedEvent'",
                String.class);
        assertEquals("approval-submitted-" + id, eventKey);
        assertEquals(1, events.received.size());
        assertEquals("退货审批", events.received.get(0).payloadAs(ApprovalSubmittedEvent.class).title());
    }

    @Test
    void compactConstructorRejectsOversizedSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitCmd(1, 1, "t", "a", "b", "c", 2L, "x".repeat(60001), null));
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitCmd(1, 1, "t", "a", "b", "c", 2L, null, "y".repeat(2001)));
        // 边界内合法
        new SubmitCmd(1, 1, "t", "a", "b", "c", 2L, "x".repeat(60000), "y".repeat(2000));
    }

    @Test
    void approveHappyPathThenTerminalGuard() {
        long id = submit();
        svc.approve(1, 2, id, "同意");
        Map<String, Object> row = jdbc.queryForMap("select status, finished_at from yz_approval where id = ?", id);
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertNotNull(row.get("finished_at"));
        Integer records = jdbc.queryForObject(
                "select count(*) from yz_approval_record where approval_id = ? and action = 2 and comment = '同意'",
                Integer.class, id);
        assertEquals(1, records);
        ApprovalFinishedEvent finished = finishedEvent(id);
        assertEquals(ApprovalFinishedEvent.RESULT_APPROVED, finished.result());
        assertEquals(2, finished.operatorId());
        // 终态再操作
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_STATE, () -> svc.approve(1, 2, id, null));
    }

    @Test
    void approveGuards() {
        long id = submit();
        // 非处理人(同组织也拒)
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_ASSIGNEE, () -> svc.approve(1, 3, id, null));
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_FOUND, () -> svc.approve(1, 2, 999999, null));
    }

    @Test
    void rejectHappyPath() {
        long id = submit();
        svc.reject(1, 2, id, "金额不符");
        Map<String, Object> row = jdbc.queryForMap("select status from yz_approval where id = ?", id);
        assertEquals(2, ((Number) row.get("status")).intValue());
        assertEquals(ApprovalFinishedEvent.RESULT_REJECTED, finishedEvent(id).result());
    }

    @Test
    void transferChangesAssigneeKeepsStatusAndChainApproves() {
        long id = submit();
        svc.transfer(1, 2, id, 3L, "休假转交");
        Map<String, Object> row = jdbc.queryForMap(
                "select assignee_id, status from yz_approval where id = ?", id);
        assertEquals(3L, ((Number) row.get("assignee_id")).longValue());
        assertEquals(0, ((Number) row.get("status")).intValue());
        Integer records = jdbc.queryForObject(
                "select count(*) from yz_approval_record where approval_id = ? and action = 4 and assignee_id = 3",
                Integer.class, id);
        assertEquals(1, records);
        // 新处理人可办结
        svc.approve(1, 3, id, null);
        assertEquals(1, ((Number) jdbc.queryForMap(
                "select status from yz_approval where id = ?", id).get("status")).intValue());
    }

    @Test
    void transferGuards() {
        long id = submit();
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_ASSIGNEE, () -> svc.transfer(1, 2, id, 2L, null));
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_ASSIGNEE, () -> svc.transfer(1, 2, id, null, null));
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_ASSIGNEE, () -> svc.transfer(1, 2, id, 4L, null));
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_ASSIGNEE, () -> svc.transfer(1, 3, id, 3L, null));
        svc.approve(1, 2, id, null);
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_STATE, () -> svc.transfer(1, 2, id, 3L, null));
    }

    @Test
    void cancelOnlyByApplicant() {
        long id = submit();
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_APPLICANT, () -> svc.cancel(1, 3, id));
        svc.cancel(1, 1, id);
        Map<String, Object> row = jdbc.queryForMap("select status from yz_approval where id = ?", id);
        assertEquals(3, ((Number) row.get("status")).intValue());
        assertEquals(ApprovalFinishedEvent.RESULT_CANCELED, finishedEvent(id).result());
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_STATE, () -> svc.cancel(1, 1, id));
    }

    @Test
    void detailVisibleToPartiesOnly() {
        long id = submit();
        svc.approve(1, 2, id, "ok");
        Map<String, Object> detail = svc.detail(1, id);
        assertEquals("发起人", detail.get("applicant_name"));
        assertTrue(((List<?>) detail.get("records")).size() >= 2);
        assertNotNull(svc.detail(2, id));
        // 无关人(含同组织)一律不可见
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_FOUND, () -> svc.detail(3, id));
        expectBiz(ApprovalService.CODE_APPROVAL_NOT_FOUND, () -> svc.detail(4, id));
    }

    @Test
    void listRolesFilter() {
        long a = submit();
        submit();
        svc.approve(1, 2, a, null);
        // todo:审批人剩一条未办
        List<Map<String, Object>> todo = svc.list(2, "todo", 1, 10);
        assertEquals(1, todo.size());
        // done:办结一条
        List<Map<String, Object>> done = svc.list(2, "done", 1, 10);
        assertEquals(1, done.size());
        // created:发起人两条
        List<Map<String, Object>> created = svc.list(1, "created", 1, 10);
        assertEquals(2, created.size());
        expectBiz(ApprovalService.CODE_APPROVAL_BAD_REQUEST, () -> svc.list(2, "bogus", 1, 10));
    }

    @Test
    void systemInitiatedApprovalHasEmptySnapshot() {
        // 系统发起(7 参兼容构造):form_data/attachments 恒空,R15 语义零变化
        long id = svc.submit(cmd(2L));
        Map<String, Object> row = jdbc.queryForMap("select form_data, attachments from yz_approval where id = ?", id);
        assertNull(row.get("form_data"));
        assertNull(row.get("attachments"));
    }
}
