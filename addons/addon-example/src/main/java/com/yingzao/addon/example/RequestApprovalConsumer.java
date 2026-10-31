package com.yingzao.addon.example;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.approval.ApprovalFinishedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 示范申请审批回程消费器:只认自己的 biz 三元组(biz_addon=example / biz_type=demo-request),
 * 按 result 回写业务状态(1/2/3);approval_id 守卫防陈旧事件误回写。姿势同 LeaveApprovalConsumer。
 */
@Component
public class RequestApprovalConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RequestApprovalConsumer.class);

    private final JdbcTemplate jdbc;

    public RequestApprovalConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApprovalFinishedEvent");
    }

    @Override
    @Transactional
    public void consume(YzEventRecord event) {
        ApprovalFinishedEvent payload = event.payloadAs(ApprovalFinishedEvent.class);
        if (!RequestService.BIZ_ADDON.equals(payload.bizAddon())
                || !RequestService.BIZ_TYPE.equals(payload.bizType())) {
            return;
        }
        long requestId;
        try {
            requestId = Long.parseLong(payload.bizId());
        } catch (NumberFormatException e) {
            log.warn("demo request approval finished with unparsable bizId={}", payload.bizId());
            return;
        }
        int status = switch (payload.result()) {
            case ApprovalFinishedEvent.RESULT_APPROVED -> RequestService.STATUS_APPROVED;
            case ApprovalFinishedEvent.RESULT_REJECTED -> RequestService.STATUS_REJECTED;
            case ApprovalFinishedEvent.RESULT_CANCELED -> RequestService.STATUS_CANCELED;
            default -> -1;
        };
        if (status < 0) {
            return;
        }
        jdbc.update(
                "update example_request set status = ? where id = ? and status = 0 and approval_id = ?",
                status, requestId, payload.approvalId());
    }
}
