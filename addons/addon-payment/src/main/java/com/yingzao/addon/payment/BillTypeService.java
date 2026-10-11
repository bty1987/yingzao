package com.yingzao.addon.payment;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.web.BizException;

/**
 * 款项类型字典(组织级):类型/收付顺序/比例模板三要素,新建类型零代码。
 * code 是协议键(建后不可改)——老 paymentType 中文字符串承担业务分支(contains("退款"))的反面教材灭绝;
 * 比例模板 = JSON [{sort,scale}],scale 为千分比,签约拆分的计算归业务插件,本域只存协议。
 */
@Service
public class BillTypeService {

    public static final int CODE_INVALID_PARAM = 270001;
    public static final int CODE_TYPE_NOT_FOUND = 270004;
    public static final int CODE_TYPE_DUP = 270005;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final ObjectMapper objectMapper;

    public BillTypeService(JdbcTemplate jdbc, MessageSource messages, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(long orgId, boolean includeDisabled) {
        return jdbc.queryForList("""
                select id, code, name, sort, scale_template, status, created_at, updated_at
                from payment_bill_type where org_id = ? %s order by sort, id
                """.formatted(includeDisabled ? "" : "and status = 0"), orgId);
    }

    @Transactional
    public long create(long orgId, String code, String name, Integer sort, String scaleTemplate) {
        requireBlank(code, name);
        require(code.matches("[a-z][a-z0-9_]{1,49}"), CODE_INVALID_PARAM, "payment.param.invalid");
        int safeSort = requireSort(sort);
        validateTemplate(scaleTemplate);
        try {
            jdbc.update("""
                    insert into payment_bill_type(org_id, code, name, sort, scale_template)
                    values (?,?,?,?,?)
                    """, orgId, code, name.trim(), safeSort, blankToNull(scaleTemplate));
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_TYPE_DUP, msg("payment.type.dup"));
        }
        return jdbc.queryForObject(
                "select id from payment_bill_type where org_id = ? and code = ?", Long.class, orgId, code);
    }

    /** 更新白名单:name/sort/scale_template 可改,code/org 不可(code 是协议键)。 */
    @Transactional
    public void update(long orgId, long id, String name, Integer sort, String scaleTemplate) {
        requireBlank(null, name);
        int safeSort = sort == null ? load(orgId, id).sort() : requireSort(sort);
        if (scaleTemplate != null) {
            validateTemplate(scaleTemplate);
        }
        int updated = jdbc.update("""
                update payment_bill_type set name = ?, sort = ?, scale_template = ?
                where id = ? and org_id = ?
                """, name != null ? name.trim() : load(orgId, id).name(), safeSort,
                scaleTemplate == null ? load(orgId, id).scaleTemplate() : blankToNull(scaleTemplate),
                id, orgId);
        if (updated == 0) {
            throw notFound();
        }
    }

    @Transactional
    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED, CODE_INVALID_PARAM, "payment.param.invalid");
        int updated = jdbc.update(
                "update payment_bill_type set status = ? where id = ? and org_id = ?", status, id, orgId);
        if (updated == 0) {
            throw notFound();
        }
    }

    private record TypeRow(long id, String code, String name, int sort, String scaleTemplate) {
    }

    private TypeRow load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, sort, scale_template from payment_bill_type where id = ? and org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> row = rows.get(0);
        return new TypeRow(((Number) row.get("id")).longValue(), (String) row.get("code"),
                (String) row.get("name"), ((Number) row.get("sort")).intValue(),
                (String) row.get("scale_template"));
    }

    private void validateTemplate(String scaleTemplate) {
        if (scaleTemplate == null || scaleTemplate.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(scaleTemplate);
        } catch (Exception e) {
            throw new BizException(CODE_INVALID_PARAM, msg("payment.param.invalid"));
        }
    }

    private int requireSort(Integer sort) {
        int value = sort == null ? 1 : sort;
        require(value >= 1, CODE_INVALID_PARAM, "payment.param.invalid");
        return value;
    }

    private void requireBlank(String code, String name) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "payment.param.invalid");
        if (code != null) {
            require(!code.isBlank(), CODE_INVALID_PARAM, "payment.param.invalid");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BizException notFound() {
        return new BizException(CODE_TYPE_NOT_FOUND, msg("payment.type.notfound"));
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }
}
