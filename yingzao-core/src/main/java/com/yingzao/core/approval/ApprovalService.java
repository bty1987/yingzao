package com.yingzao.core.approval;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 轻量审批(内核平台设施,零业务知识):单处理人状态机 0审批中 → 1通过/2驳回/3撤回,
 * 转交只改 assignee_id 不改状态。业务语义全部在提交方(biz_addon/biz_type/biz_id 纯溯源透传),
 * 办结结果经 ApprovalFinishedEvent 回插件,由插件回写自身业务状态。
 * 不做会签/条件分支/超时催办/流程编排——2026-10-10 已拍板轻量自研,不引入工作流引擎。
 */
@Service
public class ApprovalService {

    public static final int CODE_APPROVAL_NOT_FOUND = 121000;
    public static final int CODE_APPROVAL_BAD_STATE = 121001;
    public static final int CODE_APPROVAL_NOT_ASSIGNEE = 121002;
    public static final int CODE_APPROVAL_BAD_ASSIGNEE = 121003;
    public static final int CODE_APPROVAL_NOT_APPLICANT = 121004;
    public static final int CODE_APPROVAL_BAD_REQUEST = 121005;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_CANCELED = 3;

    public static final int ACTION_SUBMIT = 1;
    public static final int ACTION_APPROVE = 2;
    public static final int ACTION_REJECT = 3;
    public static final int ACTION_TRANSFER = 4;
    public static final int ACTION_CANCEL = 5;

    public record SubmitCmd(long orgId, long applicantId, String title,
                            String bizAddon, String bizType, String bizId, Long assigneeId) {
    }

    private record ApprovalRow(long id, long orgId, long applicantId, long assigneeId, int status,
                               String title, String bizAddon, String bizType, String bizId) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;

    public ApprovalService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
    }

    @Transactional
    public long submit(SubmitCmd cmd) {
        if (blank(cmd.title()) || blank(cmd.bizAddon()) || blank(cmd.bizType()) || blank(cmd.bizId())
                || cmd.assigneeId() == null) {
            throw badRequest();
        }
        requireOrgUser(cmd.assigneeId(), cmd.orgId());
        jdbc.update("""
                insert into yz_approval(org_id, title, biz_addon, biz_type, biz_id, applicant_id, assignee_id)
                values (?,?,?,?,?,?,?)
                """, cmd.orgId(), cmd.title(), cmd.bizAddon(), cmd.bizType(), cmd.bizId(),
                cmd.applicantId(), cmd.assigneeId());
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        insertRecord(id, ACTION_SUBMIT, cmd.applicantId(), null, null);
        // 事件走内核事件总线(outbox 与业务同事务,eventKey=业务幂等锚点),提交后立即投递
        bus.publish(new ApprovalSubmittedEvent(id, cmd.orgId(), cmd.title(),
                cmd.bizAddon(), cmd.bizType(), cmd.bizId(), cmd.assigneeId()),
                "approval-submitted-" + id);
        return id;
    }

    /** role=todo 待我处理 / done 我已办结(通过或驳回) / created 我发起的 */
    public List<Map<String, Object>> list(long userId, String role, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String sql = switch (role == null ? "todo" : role) {
            case "todo" -> """
                    select a.id, a.title, a.biz_addon, a.biz_type, a.biz_id, a.applicant_id,
                           ua.display_name as applicant_name, a.status, a.created_at
                    from yz_approval a join yz_user ua on ua.id = a.applicant_id
                    where a.assignee_id = ? and a.status = 0
                    order by a.id desc limit ? offset ?
                    """;
            case "done" -> """
                    select a.id, a.title, a.biz_addon, a.biz_type, a.biz_id, a.applicant_id,
                           ua.display_name as applicant_name, a.status, a.finished_at
                    from yz_approval a
                    join yz_user ua on ua.id = a.applicant_id
                    where a.status in (1, 2) and exists (
                        select 1 from yz_approval_record r
                        where r.approval_id = a.id and r.operator_id = ? and r.action in (2, 3))
                    order by a.id desc limit ? offset ?
                    """;
            case "created" -> """
                    select a.id, a.title, a.biz_addon, a.biz_type, a.biz_id, a.applicant_id,
                           ua.display_name as applicant_name, ub.display_name as assignee_name,
                           a.status, a.created_at, a.finished_at
                    from yz_approval a
                    join yz_user ua on ua.id = a.applicant_id
                    join yz_user ub on ub.id = a.assignee_id
                    where a.applicant_id = ?
                    order by a.id desc limit ? offset ?
                    """;
            default -> throw badRequest();
        };
        return jdbc.queryForList(sql, userId, safeSize, (safePage - 1) * safeSize);
    }

    public Map<String, Object> detail(long userId, long id) {
        requireParty(userId, id);
        Map<String, Object> approval = jdbc.queryForMap("""
                select a.id, a.org_id, a.title, a.biz_addon, a.biz_type, a.biz_id,
                       a.applicant_id, ua.display_name as applicant_name,
                       a.assignee_id, ub.display_name as assignee_name,
                       a.status, a.created_at, a.finished_at
                from yz_approval a
                join yz_user ua on ua.id = a.applicant_id
                join yz_user ub on ub.id = a.assignee_id
                where a.id = ?
                """, id);
        List<Map<String, Object>> records = jdbc.queryForList("""
                select r.id, r.action, r.operator_id, u.display_name as operator_name,
                       r.assignee_id, ra.display_name as assignee_name, r.comment, r.created_at
                from yz_approval_record r
                join yz_user u on u.id = r.operator_id
                left join yz_user ra on ra.id = r.assignee_id
                where r.approval_id = ?
                order by r.id
                """, id);
        approval.put("records", records);
        return approval;
    }

    @Transactional
    public void approve(long orgId, long userId, long id, String comment) {
        ApprovalRow row = requireRunning(id);
        if (row.assigneeId() != userId) {
            throw notAssignee();
        }
        int updated = jdbc.update(
                "update yz_approval set status = 1, finished_at = now() where id = ? and status = 0 and assignee_id = ?",
                id, userId);
        requireUpdated(updated, id);
        insertRecord(id, ACTION_APPROVE, userId, null, comment);
        publishFinished(row, userId, ApprovalFinishedEvent.RESULT_APPROVED);
    }

    @Transactional
    public void reject(long orgId, long userId, long id, String comment) {
        ApprovalRow row = requireRunning(id);
        if (row.assigneeId() != userId) {
            throw notAssignee();
        }
        int updated = jdbc.update(
                "update yz_approval set status = 2, finished_at = now() where id = ? and status = 0 and assignee_id = ?",
                id, userId);
        requireUpdated(updated, id);
        insertRecord(id, ACTION_REJECT, userId, null, comment);
        publishFinished(row, userId, ApprovalFinishedEvent.RESULT_REJECTED);
    }

    @Transactional
    public void transfer(long orgId, long userId, long id, Long toUserId, String comment) {
        ApprovalRow row = requireRunning(id);
        if (row.assigneeId() != userId) {
            throw notAssignee();
        }
        if (toUserId == null || toUserId == userId) {
            throw badAssignee();
        }
        requireOrgUser(toUserId, orgId);
        int updated = jdbc.update(
                "update yz_approval set assignee_id = ? where id = ? and status = 0 and assignee_id = ?",
                toUserId, id, userId);
        requireUpdated(updated, id);
        insertRecord(id, ACTION_TRANSFER, userId, toUserId, comment);
        // eventKey 带 toUserId:同一审批多次转交各自成事件(重放时新处理人通知由消费器查重去重)
        bus.publish(new ApprovalTransferredEvent(id, orgId, row.title(),
                row.bizAddon(), row.bizType(), row.bizId(), toUserId, userId),
                "approval-transferred-" + id + "-" + toUserId);
    }

    @Transactional
    public void cancel(long orgId, long userId, long id) {
        ApprovalRow row = requireRunning(id);
        if (row.applicantId() != userId) {
            throw notApplicant();
        }
        int updated = jdbc.update(
                "update yz_approval set status = 3, finished_at = now() where id = ? and status = 0 and applicant_id = ?",
                id, userId);
        requireUpdated(updated, id);
        insertRecord(id, ACTION_CANCEL, userId, null, null);
        publishFinished(row, userId, ApprovalFinishedEvent.RESULT_CANCELED);
    }

    private void publishFinished(ApprovalRow row, long operatorId, int result) {
        bus.publish(new ApprovalFinishedEvent(row.id(), row.orgId(), row.title(),
                row.bizAddon(), row.bizType(), row.bizId(),
                row.applicantId(), row.assigneeId(), operatorId, result),
                "approval-finished-" + row.id());
    }

    private void insertRecord(long approvalId, int action, long operatorId, Long assigneeId, String comment) {
        jdbc.update("""
                insert into yz_approval_record(approval_id, action, operator_id, assignee_id, comment)
                values (?,?,?,?,?)
                """, approvalId, action, operatorId, assigneeId, comment);
    }

    private ApprovalRow requireRunning(long id) {
        List<ApprovalRow> rows = jdbc.query("""
                select id, org_id, applicant_id, assignee_id, status, title, biz_addon, biz_type, biz_id
                from yz_approval where id = ?
                """, (rs, rowNum) -> new ApprovalRow(rs.getLong("id"), rs.getLong("org_id"),
                rs.getLong("applicant_id"), rs.getLong("assignee_id"), rs.getInt("status"),
                rs.getString("title"), rs.getString("biz_addon"), rs.getString("biz_type"),
                rs.getString("biz_id")), id);
        if (rows.isEmpty()) {
            throw notFound();
        }
        ApprovalRow row = rows.get(0);
        if (row.status() != STATUS_RUNNING) {
            throw badState();
        }
        return row;
    }

    private void requireUpdated(int updated, long id) {
        if (updated == 0) {
            // 并发窗口:守卫条件(状态/处理人)在读取后被改写,按当前行状态给出准确错误
            Integer status = jdbc.queryForObject("select status from yz_approval where id = ?", Integer.class, id);
            if (status == null) {
                throw notFound();
            }
            throw status == STATUS_RUNNING ? notAssignee() : badState();
        }
    }

    private void requireParty(long userId, long id) {
        Integer count = jdbc.queryForObject("""
                select count(*) from yz_approval a where a.id = ? and (
                    a.applicant_id = ? or a.assignee_id = ? or exists (
                        select 1 from yz_approval_record r
                        where r.approval_id = a.id and r.operator_id = ?))
                """, Integer.class, id, userId, userId, userId);
        if (count == null || count == 0) {
            throw notFound();
        }
    }

    private void requireOrgUser(long userId, long orgId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from yz_user where id = ? and org_id = ?", Integer.class, userId, orgId);
        if (count == null || count == 0) {
            throw badAssignee();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BizException notFound() {
        return new BizException(CODE_APPROVAL_NOT_FOUND,
                messages.getMessage("approval.notfound", null, LocaleContextHolder.getLocale()));
    }

    private BizException badState() {
        return new BizException(CODE_APPROVAL_BAD_STATE,
                messages.getMessage("approval.badstate", null, LocaleContextHolder.getLocale()));
    }

    private BizException notAssignee() {
        return new BizException(CODE_APPROVAL_NOT_ASSIGNEE,
                messages.getMessage("approval.notassignee", null, LocaleContextHolder.getLocale()));
    }

    private BizException badAssignee() {
        return new BizException(CODE_APPROVAL_BAD_ASSIGNEE,
                messages.getMessage("approval.badassignee", null, LocaleContextHolder.getLocale()));
    }

    private BizException notApplicant() {
        return new BizException(CODE_APPROVAL_NOT_APPLICANT,
                messages.getMessage("approval.notapplicant", null, LocaleContextHolder.getLocale()));
    }

    private BizException badRequest() {
        return new BizException(CODE_APPROVAL_BAD_REQUEST,
                messages.getMessage("approval.badrequest", null, LocaleContextHolder.getLocale()));
    }
}
