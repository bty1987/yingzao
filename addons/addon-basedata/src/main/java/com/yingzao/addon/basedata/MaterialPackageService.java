package com.yingzao.addon.basedata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 整装定材包服务(老 noods):pnid 两级引用保留泛化——0=自建主数据行,>0=引用行存主数据快照、
 * 仅浮动量(surplus)可改。老「company=0 即集团行」的隐式约定改显式 owner_org_id=0 平台级;
 * 后端补控权(老集团/公司控权全靠前端,updateNoods 全字段可覆写)。包成员明细挂报价项,归 addon-quotation。
 */
@Service
public class MaterialPackageService {

    public static final int CODE_PACKAGE_NOT_FOUND = 260019;
    public static final int CODE_SNAPSHOT_LOCKED = 260020;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    public static final long PNID_MASTER = 0L;
    public static final long ORG_PLATFORM = 0L;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public MaterialPackageService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    /** 自建主数据行(pnid=0,owner=本组织)。 */
    @Transactional
    public long create(long orgId, String name, Long selling, Long balance, Long surplus, Integer calc,
            Long createdBy) {
        require(name != null && !name.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        jdbc.update("""
                insert into basedata_material_package
                    (pnid, owner_org_id, org_id, name, selling, balance, surplus, calc, status, created_by)
                values (?,?,?,?,?,?,?,?,0,?)
                """, PNID_MASTER, orgId, orgId, name.trim(), orZero(selling), orZero(balance),
                orZero(surplus), calc == null ? 0 : calc, createdBy);
        return jdbc.queryForObject("""
                select id from basedata_material_package where pnid = 0 and name = ? and owner_org_id = ?
                order by id desc limit 1
                """, Long.class, name.trim(), orgId);
    }

    /** 引用平台/他组织主数据行建引用行:名称与价格字段复制快照,此后仅 surplus 可改。 */
    @Transactional
    public long createReference(long orgId, long masterId, Long surplus, Long createdBy) {
        Map<String, Object> master = loadVisible(orgId, masterId);
        require(((Number) master.get("pnid")).longValue() == PNID_MASTER, CODE_PACKAGE_NOT_FOUND,
                "mpackage.notfound");
        jdbc.update("""
                insert into basedata_material_package
                    (pnid, owner_org_id, org_id, name, selling, balance, surplus, calc, status, created_by)
                values (?,?,?,?,?,?,?,?,0,?)
                """, masterId, ((Number) master.get("owner_org_id")).longValue(), orgId, master.get("name"),
                master.get("selling"), master.get("balance"), orZero(surplus), master.get("calc"), createdBy);
        return jdbc.queryForObject("""
                select id from basedata_material_package where pnid = ? and org_id = ? order by id desc limit 1
                """, Long.class, masterId, orgId);
    }

    /**
     * 编辑:主数据行(owner=本组织)全字段;引用行(本组织)仅 surplus(老 updateNoods 全字段可覆写、
     * 控权全前端的反面教材,引用行改其它字段显式拒绝 260020 而非静默忽略)。
     * 平台级主数据行(owner_org=0)组织侧不可变更。
     */
    @Transactional
    public void update(long orgId, long id, String name, Long selling, Long balance, Long surplus, Integer calc) {
        Map<String, Object> pkg = load(orgId, id);
        boolean reference = ((Number) pkg.get("pnid")).longValue() != PNID_MASTER;
        int updated;
        if (reference) {
            require(name == null && selling == null && balance == null && calc == null,
                    CODE_SNAPSHOT_LOCKED, "mpackage.snapshotLocked");
            require(surplus != null, CategoryService.CODE_INVALID_PARAM, "param.invalid");
            updated = jdbc.update(
                    "update basedata_material_package set surplus = ? where id = ? and org_id = ? and pnid != 0",
                    surplus, id, orgId);
        } else {
            require(name != null && !name.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
            updated = jdbc.update("""
                    update basedata_material_package set name = ?, selling = ?, balance = ?, surplus = ?, calc = ?
                    where id = ? and pnid = 0 and owner_org_id = ?
                    """, name.trim(), orZero(selling), orZero(balance), orZero(surplus),
                    calc == null ? 0 : calc, id, orgId);
        }
        if (updated == 0) {
            // 平台级主数据行组织侧不可变更
            throw new BizException(CODE_PACKAGE_NOT_FOUND, msg("mpackage.notfound"));
        }
    }

    /** 停用主数据行不级联引用行(引用行是快照,已建不受影响;老系统同理);仅归属组织可操作。 */
    @Transactional
    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, id);
        int updated = jdbc.update("""
                update basedata_material_package set status = ?
                where id = ? and (pnid != 0 and org_id = ? or pnid = 0 and owner_org_id = ?)
                """, status, id, orgId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_PACKAGE_NOT_FOUND, msg("mpackage.notfound"));
        }
    }

    /** 可见列表:本组织行(自建主数据+引用行)∪ 平台级主数据行(owner_org=0)。 */
    public Map<String, Object> page(long orgId, String keyword, Integer status, Boolean onlyMaster,
            int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where (p.org_id = ? or (p.pnid = 0 and p.owner_org_id = ?))");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        args.add(ORG_PLATFORM);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and p.name like concat('%', ?, '%')");
            args.add(keyword.trim());
        }
        if (status != null) {
            where.append(" and p.status = ?");
            args.add(status);
        }
        if (Boolean.TRUE.equals(onlyMaster)) {
            where.append(" and p.pnid = 0");
        }
        Long total = jdbc.queryForObject("select count(*) from basedata_material_package p" + where, Long.class,
                args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.id, p.pnid, p.owner_org_id, p.org_id, p.name, p.selling, p.balance, p.surplus,
                       p.calc, p.status, m.name as master_name, p.created_at
                from basedata_material_package p
                left join basedata_material_package m on m.id = p.pnid
                """ + where + " order by p.id desc limit ? offset ?", allArgs.toArray());
        rows.forEach(r -> r.put("level", ((Number) r.get("pnid")).longValue() == PNID_MASTER ? 0 : 1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, pnid, owner_org_id, org_id, name, selling, balance, surplus, calc, status
                from basedata_material_package
                where id = ? and (org_id = ? or (pnid = 0 and owner_org_id = ?))
                """, id, orgId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_PACKAGE_NOT_FOUND, msg("mpackage.notfound"));
        }
        return rows.get(0);
    }

    /** 引用来源须为可见的启用主数据行。 */
    private Map<String, Object> loadVisible(long orgId, long id) {
        Map<String, Object> master = load(orgId, id);
        require(((Number) master.get("status")).intValue() == STATUS_ENABLED, CODE_PACKAGE_NOT_FOUND,
                "mpackage.notfound");
        return master;
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
