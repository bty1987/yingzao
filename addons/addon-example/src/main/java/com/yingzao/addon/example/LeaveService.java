package com.yingzao.addon.example;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.web.BizException;

/**
 * 请假示例服务:演示业务插件接入内核轻量审批的标准组合——
 * 业务单(example_leave)与审批单(yz_approval)同事务落库,提交时携带 biz_addon/biz_type/biz_id 溯源三元组;
 * 办结结果由 {@link LeaveApprovalListener} 监听 ApprovalFinishedEvent 回写业务状态。
 * 本 addon 是面向插件开发者的示范工程(非业务插件):表与接口刻意保持最小。
 */
@Service
public class LeaveService {

    public static final int CODE_LEAVE_NOT_FOUND = 290001;
    public static final int CODE_LEAVE_FINISHED = 290002;
    public static final int CODE_LEAVE_BAD_REQUEST = 290003;
    public static final int CODE_LEAVE_ASSIGNEE_INVALID = 290004;
    public static final int CODE_LEAVE_NOT_OWNER = 290005;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_CANCELED = 3;

    public static final String BIZ_ADDON = "example";
    public static final String BIZ_TYPE = "leave";

    public record CreateCmd(long orgId, long applicantId, String reason, Integer days, Long assigneeId) {
    }

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final MessageSource messages;

    public LeaveService(JdbcTemplate jdbc, ApprovalService approvals, MessageSource messages) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.messages = messages;
    }

    /** 发起请假 = 建业务单 + 提交内核审批单,同事务;审批单 id 回填业务单便于撤回。 */
    @Transactional
    public long create(CreateCmd cmd) {
        if (cmd.reason() == null || cmd.reason().isBlank() || cmd.days() == null || cmd.days() <= 0
                || cmd.assigneeId() == null) {
            throw badRequest();
        }
        requireOrgUser(cmd.assigneeId(), cmd.orgId());
        jdbc.update(
                "insert into example_leave(org_id, applicant_id, assignee_id, reason, days) values (?,?,?,?,?)",
                cmd.orgId(), cmd.applicantId(), cmd.assigneeId(), cmd.reason(), cmd.days());
        long leaveId = jdbc.queryForObject("select last_insert_id()", Long.class);
        String applicantName = jdbc.queryForObject(
                "select display_name from yz_user where id = ?", String.class, cmd.applicantId());
        var locale = LocaleContextHolder.getLocale();
        String title = messages.getMessage("leave.approval.title",
                new Object[] { applicantName, cmd.days() }, locale);
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(cmd.orgId(), cmd.applicantId(),
                title, BIZ_ADDON, BIZ_TYPE, String.valueOf(leaveId), cmd.assigneeId()));
        jdbc.update("update example_leave set approval_id = ? where id = ?", approvalId, leaveId);
        return leaveId;
    }

    /** 我的请假单(申请人视角,无跨人查看)。 */
    public List<Map<String, Object>> listMine(long userId) {
        return jdbc.queryForList("""
                select l.id, l.reason, l.days, l.status, l.approval_id,
                       u.display_name as assignee_name, l.created_at, l.finished_at
                from example_leave l join yz_user u on u.id = l.assignee_id
                where l.applicant_id = ?
                order by l.id desc
                """, userId);
    }

    /**
     * 撤回请假:业务侧只做归属与状态守卫,撤回动作委托内核审批撤回,
     * 回写由 ApprovalFinishedEvent 事务提交后统一触发(与内核侧直接撤回同一条路)。
     * 审批单已被办结时透传内核错误码(如 121001)。
     */
    @Transactional
    public void cancel(long orgId, long userId, long leaveId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select applicant_id, status, approval_id from example_leave where id = ? and org_id = ? limit 1",
                leaveId, orgId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("applicant_id")).longValue() != userId) {
            throw notOwner();
        }
        if (((Number) row.get("status")).intValue() != STATUS_RUNNING) {
            throw finished();
        }
        approvals.cancel(orgId, userId, ((Number) row.get("approval_id")).longValue());
    }

    private void requireOrgUser(long userId, long orgId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from yz_user where id = ? and org_id = ?", Integer.class, userId, orgId);
        if (count == null || count == 0) {
            throw new BizException(CODE_LEAVE_ASSIGNEE_INVALID,
                    messages.getMessage("leave.assignee.invalid", null, LocaleContextHolder.getLocale()));
        }
    }

    private BizException notFound() {
        return new BizException(CODE_LEAVE_NOT_FOUND,
                messages.getMessage("leave.notfound", null, LocaleContextHolder.getLocale()));
    }

    private BizException finished() {
        return new BizException(CODE_LEAVE_FINISHED,
                messages.getMessage("leave.finished", null, LocaleContextHolder.getLocale()));
    }

    private BizException badRequest() {
        return new BizException(CODE_LEAVE_BAD_REQUEST,
                messages.getMessage("leave.badrequest", null, LocaleContextHolder.getLocale()));
    }

    private BizException notOwner() {
        return new BizException(CODE_LEAVE_NOT_OWNER,
                messages.getMessage("leave.notowner", null, LocaleContextHolder.getLocale()));
    }
}
