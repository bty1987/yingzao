package com.yingzao.addon.example;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.yingzao.core.approval.ApprovalFinishedEvent;

/**
 * 审批办结回写:插件监听内核 ApprovalFinishedEvent,按 result 回写业务状态——
 * 这是「业务插件接入轻量审批」的回程车。只处理自己的 biz 三元组,其他插件的审批一律忽略。
 * 军规 8:AFTER_COMMIT 上的 @Transactional 必须 REQUIRES_NEW(Spring 6.1 硬约束)。
 */
@Component
public class LeaveApprovalListener {

    private final JdbcTemplate jdbc;

    public LeaveApprovalListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApprovalFinished(ApprovalFinishedEvent event) {
        if (!LeaveService.BIZ_ADDON.equals(event.bizAddon()) || !LeaveService.BIZ_TYPE.equals(event.bizType())) {
            return;
        }
        int status;
        if (event.result() == ApprovalFinishedEvent.RESULT_APPROVED) {
            status = LeaveService.STATUS_APPROVED;
        } else if (event.result() == ApprovalFinishedEvent.RESULT_REJECTED) {
            status = LeaveService.STATUS_REJECTED;
        } else if (event.result() == ApprovalFinishedEvent.RESULT_CANCELED) {
            status = LeaveService.STATUS_CANCELED;
        } else {
            return;
        }
        long leaveId;
        try {
            leaveId = Long.parseLong(event.bizId());
        } catch (NumberFormatException e) {
            System.err.println("[example] leave approval finished with unparsable bizId="
                    + event.bizId() + ", approvalId=" + event.approvalId());
            return;
        }
        jdbc.update("update example_leave set status = ?, finished_at = now() where id = ? and status = 0",
                status, leaveId);
    }
}
