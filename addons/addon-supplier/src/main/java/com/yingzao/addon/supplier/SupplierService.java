package com.yingzao.addon.supplier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.SupplierCreatedEvent;
import com.yingzao.addons.api.event.SupplierStatusChangedEvent;
import com.yingzao.addons.api.event.SupplierUpdatedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 厂商主数据服务:独立档案实体(废老系统「厂商即用户」寄生)。
 * 修老三债:①addSupplier 应用层零查重 → 三路查重下沉 DB 唯一键兜底并发;
 * ②counting 计数器读改写竞态取号 → 编号按 id 生成(S+8位)零计数器;
 * ③「停用」借员工离职语义无专属状态机 → status 条件 UPDATE 显式启停,
 *   停用只挡新挂单(业务插件经 SupplierApi.checkActive 闸),存量单不受影响。
 * ext_no = 对外系统编号一等公民(老 oldUserId 语义);upsert 收编 seiya 同步通道思想
 * (命中更新/否则新建,并发同键 DB 唯一兜底重查回显)。
 */
@Service
public class SupplierService {

    public static final int CODE_INVALID_PARAM = 340001;
    public static final int CODE_SUPPLIER_NOT_FOUND = 340002;
    public static final int CODE_SUPPLIER_EXISTS = 340003;
    public static final int CODE_EXT_NO_EXISTS = 340004;
    public static final int CODE_CREDIT_CODE_EXISTS = 340005;
    public static final int CODE_SUPPLIER_DISABLED = 340006;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;

    public SupplierService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    public record SaveCmd(String name, String fullName, String contact, String phone,
                          String address, String creditCode, String extNo, String remark) {
    }

    // ---- 建档 ----

    @Transactional
    public long create(long orgId, SaveCmd cmd, Long createdBy) {
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        requireDuplicateFree(orgId, null, cmd.extNo(), cmd.creditCode());
        try {
            jdbc.update("""
                    insert into supplier_supplier
                        (org_id, no, name, full_name, contact, phone, address, credit_code, ext_no,
                         status, remark, created_by)
                    values (?, 'S00000000', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, orgId, cmd.name().trim(), blankToNull(cmd.fullName()), blankToNull(cmd.contact()),
                    blankToNull(cmd.phone()), blankToNull(cmd.address()), blankToNull(cmd.creditCode()),
                    blankToNull(cmd.extNo()), STATUS_ENABLED, blankToNull(cmd.remark()), createdBy);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            // 编号按 id 生成,无计数器读改写竞态(老 counting type=47 教训)
            jdbc.update("update supplier_supplier set no = concat('S', lpad(id, 8, '0')) where id = ?", id);
            bus.publish(new SupplierCreatedEvent(id, orgId, "S" + String.format("%08d", id), cmd.name().trim()),
                    "supplier-created-" + id);
            return id;
        } catch (DuplicateKeyException e) {
            throw duplicate(orgId, null, cmd.extNo(), cmd.creditCode());
        }
    }

    /** 外部同步通道:ext_no 幂等 upsert(命中更新可变字段/否则新建;并发同键 DB 兜底重查回显)。 */
    @Transactional
    public Map<String, Object> upsertByExtNo(long orgId, SaveCmd cmd) {
        require(cmd.extNo() != null && !cmd.extNo().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        String extNo = cmd.extNo().trim();
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from supplier_supplier where ext_no = ?", extNo);
        if (!found.isEmpty()) {
            long id = ((Number) found.get(0).get("id")).longValue();
            jdbc.update("""
                    update supplier_supplier set name = ?, full_name = ?, contact = ?, phone = ?,
                        address = ?, credit_code = ?, remark = ? where id = ?
                    """, cmd.name().trim(), blankToNull(cmd.fullName()), blankToNull(cmd.contact()),
                    blankToNull(cmd.phone()), blankToNull(cmd.address()), blankToNull(cmd.creditCode()),
                    blankToNull(cmd.remark()), id);
            bus.publish(new SupplierUpdatedEvent(id, orgId), "supplier-updated-" + id);
            return upsertResult(id, false);
        }
        try {
            long id = create(orgId, cmd, null);
            return upsertResult(id, true);
        } catch (BizException e) {
            // 并发同 ext_no 撞全局唯一:对手事务已建,重查回显(幂等)
            if (e.getCode() == CODE_EXT_NO_EXISTS || e.getCode() == CODE_SUPPLIER_EXISTS) {
                List<Map<String, Object>> raced = jdbc.queryForList(
                        "select id from supplier_supplier where ext_no = ?", extNo);
                if (!raced.isEmpty()) {
                    long id = ((Number) raced.get(0).get("id")).longValue();
                    return upsertResult(id, false);
                }
            }
            throw e;
        }
    }

    private Map<String, Object> upsertResult(long supplierId, boolean created) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", supplierId);
        m.put("created", created);
        return m;
    }

    // ---- 编辑/启停 ----

    /** 编辑白名单:name/fullName/contact/phone/address/creditCode/extNo/remark;no/status/org 不可被 VO 覆写。 */
    @Transactional
    public void update(long orgId, long id, SaveCmd cmd) {
        require(supplierExists(orgId, id), CODE_SUPPLIER_NOT_FOUND, "supplier.notfound");
        if (cmd.name() != null && !cmd.name().isBlank()) {
            requireDuplicateFree(orgId, id, null, null, cmd.name().trim());
        }
        Map<String, Object> current = jdbc.queryForMap("""
                select name, ext_no, credit_code, full_name, contact, phone, address, remark
                from supplier_supplier where id = ? and org_id = ?
                """, id, orgId);
        // 传 null = 保留现值(非空白才覆盖;防「不带字段的更新清空数据」老坑)
        String newExtNo = cmd.extNo() == null ? (String) current.get("ext_no") : blankToNull(cmd.extNo());
        String newCredit = cmd.creditCode() == null
                ? (String) current.get("credit_code") : blankToNull(cmd.creditCode());
        if (newCredit != null) {
            requireCreditFree(orgId, id, newCredit);
        }
        if (newExtNo != null && !newExtNo.equals(current.get("ext_no"))) {
            requireExtFree(newExtNo, id);
        }
        try {
            jdbc.update("""
                    update supplier_supplier set name = ?, full_name = ?, contact = ?, phone = ?,
                        address = ?, credit_code = ?, ext_no = ?, remark = ? where id = ? and org_id = ?
                    """,
                    firstNonBlank(cmd.name(), current.get("name")),
                    firstNonNull(cmd.fullName() == null ? null : blankToNull(cmd.fullName()), current.get("full_name")),
                    firstNonNull(cmd.contact() == null ? null : blankToNull(cmd.contact()), current.get("contact")),
                    firstNonNull(cmd.phone() == null ? null : blankToNull(cmd.phone()), current.get("phone")),
                    firstNonNull(cmd.address() == null ? null : blankToNull(cmd.address()), current.get("address")),
                    newCredit, newExtNo,
                    firstNonNull(cmd.remark() == null ? null : blankToNull(cmd.remark()), current.get("remark")),
                    id, orgId);
        } catch (DuplicateKeyException e) {
            throw duplicate(orgId, id, newExtNo, newCredit);
        }
        bus.publish(new SupplierUpdatedEvent(id, orgId), "supplier-updated-" + id);
    }

    /** 启停(条件语义:同态重放幂等不重复发事件);停用不删不藏,存量单照常展示。 */
    @Transactional
    public void changeStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED, CODE_INVALID_PARAM, "param.invalid");
        require(supplierExists(orgId, id), CODE_SUPPLIER_NOT_FOUND, "supplier.notfound");
        int updated = jdbc.update(
                "update supplier_supplier set status = ? where id = ? and org_id = ? and status <> ?",
                status, id, orgId, status);
        if (updated > 0) {
            bus.publish(new SupplierStatusChangedEvent(id, orgId, status),
                    "supplier-status-" + id + "-" + status);
        }
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, String keyword, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where s.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (s.name like concat('%', ?, '%') or s.no = ? or s.ext_no = ?)");
            args.add(keyword.trim());
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        if (status != null) {
            where.append(" and s.status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from supplier_supplier s" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.no, s.name, s.full_name, s.contact, s.phone, s.ext_no, s.status, s.created_at
                from supplier_supplier s
                """ + where + " order by s.id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> get(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.no, s.name, s.full_name, s.contact, s.phone, s.address,
                       s.credit_code, s.ext_no, s.status, s.remark, s.created_at, s.updated_at
                from supplier_supplier s where s.id = ? and s.org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_SUPPLIER_NOT_FOUND, msg("supplier.notfound"));
        }
        return rows.get(0);
    }

    // ---- 校验(供本插件品牌关联/条款服务复用)----

    public Map<String, Object> loadActive(long orgId, long id) {
        Map<String, Object> supplier = load(orgId, id);
        require(((Number) supplier.get("status")).intValue() == STATUS_ENABLED,
                CODE_SUPPLIER_DISABLED, "supplier.disabled");
        return supplier;
    }

    public Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, no, name, status from supplier_supplier where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_SUPPLIER_NOT_FOUND, msg("supplier.notfound"));
        }
        return rows.get(0);
    }

    private boolean supplierExists(long orgId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from supplier_supplier where id = ? and org_id = ?", Integer.class, id, orgId);
        return count != null && count > 0;
    }

    /** 三路查重应用层预检(给准确错误码),DB 唯一键兜底并发。 */
    private void requireDuplicateFree(long orgId, Long selfId, String extNo, String creditCode) {
        if (extNo != null && !extNo.isBlank()) {
            requireExtFree(extNo.trim(), selfId);
        }
        if (creditCode != null && !creditCode.isBlank()) {
            requireCreditFree(orgId, selfId, creditCode.trim());
        }
    }

    private void requireExtFree(String extNo, Long selfId) {
        Integer count = selfId == null
                ? jdbc.queryForObject("select count(*) from supplier_supplier where ext_no = ?",
                        Integer.class, extNo)
                : jdbc.queryForObject("select count(*) from supplier_supplier where ext_no = ? and id <> ?",
                        Integer.class, extNo, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_EXT_NO_EXISTS, msg("extno.exists"));
        }
    }

    private void requireCreditFree(long orgId, Long selfId, String creditCode) {
        Integer count = selfId == null
                ? jdbc.queryForObject(
                        "select count(*) from supplier_supplier where org_id = ? and credit_code = ?",
                        Integer.class, orgId, creditCode)
                : jdbc.queryForObject(
                        "select count(*) from supplier_supplier where org_id = ? and credit_code = ? and id <> ?",
                        Integer.class, orgId, creditCode, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_CREDIT_CODE_EXISTS, msg("creditcode.exists"));
        }
    }

    private void requireDuplicateFree(long orgId, Long selfId, String extNo, String creditCode, String name) {
        Integer count = selfId == null
                ? jdbc.queryForObject("select count(*) from supplier_supplier where org_id = ? and name = ?",
                        Integer.class, orgId, name)
                : jdbc.queryForObject(
                        "select count(*) from supplier_supplier where org_id = ? and name = ? and id <> ?",
                        Integer.class, orgId, name, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_SUPPLIER_EXISTS, msg("supplier.exists"));
        }
        requireDuplicateFree(orgId, selfId, extNo, creditCode);
    }

    /** 并发撞唯一键后定位冲突字段(错误码必须定位到 name/ext_no/credit_code 各自,修老 190008/190036 同义双码的反面)。 */
    private BizException duplicate(long orgId, Long selfId, String extNo, String creditCode) {
        if (extNo != null && !extNo.isBlank() && !extFree(orgId, selfId, "ext_no", extNo)) {
            return new BizException(CODE_EXT_NO_EXISTS, msg("extno.exists"));
        }
        if (creditCode != null && !creditCode.isBlank() && !extFree(orgId, selfId, "credit_code", creditCode)) {
            return new BizException(CODE_CREDIT_CODE_EXISTS, msg("creditcode.exists"));
        }
        return new BizException(CODE_SUPPLIER_EXISTS, msg("supplier.exists"));
    }

    private boolean extFree(long orgId, Long selfId, String column, String value) {
        String sql = "select count(*) from supplier_supplier where " + column + " = ?"
                + (selfId == null ? "" : " and id <> ?");
        Integer count = selfId == null
                ? jdbc.queryForObject(sql, Integer.class, value)
                : jdbc.queryForObject(sql, Integer.class, value, selfId);
        return count == null || count == 0;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Object firstNonBlank(String value, Object fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private Object firstNonNull(Object value, Object fallback) {
        return value != null ? value : fallback;
    }
}
