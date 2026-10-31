package com.yingzao.addon.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.web.BizException;

/**
 * 示范申请单服务(docs/15 R15~R16:schema 驱动表单审批的插件侧参考实现)。
 * 发起 = 业务单落库 + 同事务 approvals.submit(formData/attachments 透传内核快照)——
 * 与 LeaveService 同一组合姿势;差异仅在审批单携带表单快照(移动端审批人直接看表单内容)。
 * 表单 schema 在 addon.yml approval-forms 段声明,本服务不感知 schema 细节,
 * 只按业务字段校验/落库(演示「schema 渲染在端、解释在端、插件只认自己的字段」)。
 */
@Service
public class RequestService {

    public static final int CODE_REQUEST_BAD = 290011;
    public static final int CODE_REQUEST_NOT_FOUND = 290012;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_CANCELED = 3;

    public static final String BIZ_ADDON = "example";
    public static final String BIZ_TYPE = "demo-request";

    public record CreateCmd(Long orderId, String orderLabel, String village, String customer,
                            String reason, Long amountYuan, List<Long> images, Long assigneeId) {
    }

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final MessageSource messages;
    private final ObjectMapper objectMapper;

    public RequestService(JdbcTemplate jdbc, ApprovalService approvals, MessageSource messages,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    /** 发起示范申请:reason 必填,amount 元→厘 ×1000(插件自有口径,与移动端零换算军规不冲突——解释权在端点)。 */
    @Transactional
    public long create(long orgId, long applicantId, CreateCmd cmd) {
        if (cmd.reason() == null || cmd.reason().isBlank() || cmd.assigneeId() == null) {
            throw bad();
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from yz_user where id = ? and org_id = ?", Integer.class, cmd.assigneeId(), orgId);
        if (count == null || count == 0) {
            throw bad();
        }
        if (cmd.amountYuan() != null && cmd.amountYuan() < 0) {
            throw bad();
        }
        Long amount = cmd.amountYuan() == null ? null : cmd.amountYuan() * 1000;
        jdbc.update("""
                insert into example_request(org_id, applicant_id, order_id, order_label, village, customer,
                                            reason, amount, attachments)
                values (?,?,?,?,?,?,?,?,?)
                """, orgId, applicantId, cmd.orderId(), cmd.orderLabel(), cmd.village(), cmd.customer(),
                cmd.reason().trim(), amount, toJson(cmd.images()));
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        String applicantName = jdbc.queryForObject(
                "select display_name from yz_user where id = ?", String.class, applicantId);
        String title = messages.getMessage("request.approval.title",
                new Object[] { applicantName, cmd.reason().trim() }, LocaleContextHolder.getLocale());
        // 表单值快照:业务字段回传 JSON(键与 schema 字段 id 一致,审批详情按 schema 渲染 label)
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("orderId", cmd.orderId());
        formData.put("orderLabel", cmd.orderLabel());
        formData.put("village", cmd.village());
        formData.put("customer", cmd.customer());
        formData.put("reason", cmd.reason().trim());
        formData.put("amount", cmd.amountYuan());
        formData.put("images", cmd.images());
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(orgId, applicantId, title,
                BIZ_ADDON, BIZ_TYPE, String.valueOf(id), cmd.assigneeId(), toJson(formData), toJson(cmd.images())));
        jdbc.update("update example_request set approval_id = ? where id = ?", approvalId, id);
        return approvalId;
    }

    /** 我的示范申请(发起人视角)。 */
    public List<Map<String, Object>> listMine(long userId) {
        return jdbc.queryForList("""
                select r.id, r.order_id, r.order_label, r.village, r.customer, r.reason, r.amount,
                       r.attachments, r.approval_id, r.status, r.created_at
                from example_request r
                where r.applicant_id = ?
                order by r.id desc
                """, userId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialization failed", e);
        }
    }

    private BizException bad() {
        return new BizException(CODE_REQUEST_BAD,
                messages.getMessage("request.bad", null, LocaleContextHolder.getLocale()));
    }
}
