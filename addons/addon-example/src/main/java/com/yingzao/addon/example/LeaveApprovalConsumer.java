package com.yingzao.addon.example;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 审批办结回写消费器(内核事件总线 SPI):插件消费内核 ApprovalFinishedEvent,按 result 回写业务状态——
 * 这是「业务插件接入轻量审批」的回程车。只处理自己的 biz 三元组,其他插件的审批一律忽略。
 * 多级串联(RFC §6.8 组合方式):L1 审批通过且业务须二级审批时,在同一消费里用内核 submit 派生
 * L2 审批单并改挂业务单 approval_id——内核审批单零改动,审批深度完全由插件组合。
 * 所有回写都带 approval_id 守卫(只认「当前挂着的那张审批单」的办结事件),陈旧事件天然被拒。
 * 取代原 LeaveApprovalListener 的 Spring AFTER_COMMIT 监听(第 39 次运行迁总线):
 * 回写逻辑在本消费器内直接操作 JDBC,故 consume 带 @Transactional(当前无事务上下文,进方法即新建,
 * 语义等价旧 REQUIRES_NEW)保「派生 L2 + 改挂」原子;失败由总线重试,守卫保证重放安全。
 */
@Component
public class LeaveApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LeaveApprovalConsumer.class);

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final MessageSource messages;

    public LeaveApprovalConsumer(JdbcTemplate jdbc, @Lazy ApprovalService approvals, MessageSource messages) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.messages = messages;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    @Transactional
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!LeaveService.BIZ_ADDON.equals(payload.bizAddon()) || !LeaveService.BIZ_TYPE.equals(payload.bizType())) {
            return;
        }
        long leaveId;
        try {
            leaveId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.warn("leave approval finished with unparsable bizId={}, approvalId={}",
                    payload.bizId(), payload.approvalId());
            return;
        }
        if (payload.result() == ApprovalFinishedEvent.RESULT_APPROVED) {
            onApproved(payload, leaveId);
        } else if (payload.result() == ApprovalFinishedEvent.RESULT_REJECTED) {
            finish(leaveId, payload.approvalId(), LeaveService.STATUS_REJECTED);
        } else if (payload.result() == ApprovalFinishedEvent.RESULT_CANCELED) {
            finish(leaveId, payload.approvalId(), LeaveService.STATUS_CANCELED);
        }
    }

    /** 一级通过:长请假派生二级审批单并改挂;否则(或二级通过)业务终态=已通过。 */
    private void onApproved(ApprovalFinishedEvent event, long leaveId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, applicant_id, manager_id, approval_id, approval_level, days, status
                from example_leave where id = ?
                """, leaveId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> leave = rows.get(0);
        long currentApprovalId = ((Number) leave.get("approval_id")).longValue();
        if (currentApprovalId != event.approvalId()
                || ((Number) leave.get("status")).intValue() != LeaveService.STATUS_RUNNING) {
            return;
        }
        boolean needSecondLevel = ((Number) leave.get("approval_level")).intValue() == LeaveService.LEVEL_FIRST
                && ((Number) leave.get("days")).intValue() >= LeaveService.MULTI_LEVEL_MIN_DAYS;
        if (!needSecondLevel) {
            finish(leaveId, currentApprovalId, LeaveService.STATUS_APPROVED);
            return;
        }
        long managerId = ((Number) leave.get("manager_id")).longValue();
        String applicantName = jdbc.queryForObject(
                "select display_name from yz_user where id = ?", String.class, leave.get("applicant_id"));
        String title = messages.getMessage("leave.approval.title.l2",
                new Object[] { applicantName, ((Number) leave.get("days")).intValue() },
                LocaleContextHolder.getLocale());
        long secondId = approvals.submit(new ApprovalService.SubmitCmd(
                event.orgId(), event.applicantId(), title,
                LeaveService.BIZ_ADDON, LeaveService.BIZ_TYPE, event.bizId(), managerId));
        int updated = jdbc.update(
                "update example_leave set approval_id = ?, approval_level = ? where id = ? and approval_id = ?",
                secondId, LeaveService.LEVEL_SECOND, leaveId, currentApprovalId);
        if (updated == 0) {
            // 并发窗口:一级通过与业务侧撤回竞争失败,派生出的二级审批单成为悬置单,走内核撤回兜底
            // (保留流水与事件;其 CANCELED 事件会被上方 approval_id 守卫拒掉,不会误回写业务单)
            approvals.cancel(event.orgId(), event.applicantId(), secondId);
            log.warn("leave {} level-up lost the race, spawned second approval {} canceled", leaveId, secondId);
        }
    }

    /** 业务终态回写:approval_id 守卫,只认当前挂着的审批单。 */
    private void finish(long leaveId, long approvalId, int status) {
        jdbc.update(
                "update example_leave set status = ?, finished_at = now() where id = ? and status = 0 and approval_id = ?",
                status, leaveId, approvalId);
    }
}
