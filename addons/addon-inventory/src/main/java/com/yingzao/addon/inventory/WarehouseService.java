package com.yingzao.addon.inventory;

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
 * 仓库档案与可见性授权:全链 org_id 隔离(修老 storehouse 无 company 多租户半成品)。
 * 授权 = 可见性闸(库存查询按授权仓过滤,无授权空列表);写操作 V1 不校验授权,
 * 显式声明沿老语义(rules/inventory.md §2.4 两选一的保守侧,升格真闸门随 RBAC v2)。
 */
@Service
public class WarehouseService {

    public static final int CODE_NOT_FOUND = 310010;
    public static final int CODE_CODE_EXISTS = 310011;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public WarehouseService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public List<Map<String, Object>> list(long orgId, boolean includeDisabled) {
        return jdbc.queryForList("""
                select id, code, name, status, created_at from inventory_warehouse
                where org_id = ? %s order by id
                """.formatted(includeDisabled ? "" : "and status = 0"), orgId);
    }

    @Transactional
    public long create(long orgId, String code, String name, long operatorId) {
        require(notBlank(code) && code.trim().length() <= 16, PARAM, "inventory.param.invalid");
        require(notBlank(name) && name.trim().length() <= 64, PARAM, "inventory.param.invalid");
        String normalized = code.trim();
        Integer dup = jdbc.queryForObject(
                "select count(*) from inventory_warehouse where org_id = ? and code = ?",
                Integer.class, orgId, normalized);
        require(dup == null || dup == 0, CODE_CODE_EXISTS, "inventory.warehouse.codeExists");
        jdbc.update("insert into inventory_warehouse(org_id, code, name) values (?,?,?)",
                orgId, normalized, name.trim());
        return jdbc.queryForObject(
                "select id from inventory_warehouse where org_id = ? and code = ?",
                Long.class, orgId, normalized);
    }

    @Transactional
    public void update(long orgId, long warehouseId, String name, Integer status) {
        load(orgId, warehouseId);
        if (name != null) {
            require(notBlank(name) && name.trim().length() <= 64, PARAM, "inventory.param.invalid");
            jdbc.update("update inventory_warehouse set name = ? where id = ?", name.trim(), warehouseId);
        }
        if (status != null) {
            require(status == STATUS_ENABLED || status == STATUS_DISABLED, PARAM, "inventory.param.invalid");
            jdbc.update("update inventory_warehouse set status = ? where id = ?", status, warehouseId);
        }
    }

    // ---- 授权(可见性) ----

    public List<Map<String, Object>> listUsers(long orgId, long warehouseId) {
        load(orgId, warehouseId);
        return jdbc.queryForList("""
                select u.user_id, us.display_name, u.created_at
                from inventory_warehouse_user u join yz_user us on us.id = u.user_id
                where u.org_id = ? and u.warehouse_id = ? order by u.user_id
                """, orgId, warehouseId);
    }

    @Transactional
    public void grant(long orgId, long warehouseId, Long userId) {
        load(orgId, warehouseId);
        require(userId != null, PARAM, "inventory.param.invalid");
        Integer count = jdbc.queryForObject("select count(*) from yz_user where id = ? and org_id = ?",
                Integer.class, userId, orgId);
        require(count != null && count > 0, PARAM, "inventory.param.invalid");
        try {
            jdbc.update("""
                    insert into inventory_warehouse_user(org_id, warehouse_id, user_id) values (?,?,?)
                    """, orgId, warehouseId, userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 重复授权幂等
        }
    }

    @Transactional
    public void revoke(long orgId, long warehouseId, long userId) {
        load(orgId, warehouseId);
        jdbc.update("""
                delete from inventory_warehouse_user
                where org_id = ? and warehouse_id = ? and user_id = ?
                """, orgId, warehouseId, userId);
    }

    private Map<String, Object> load(long orgId, long warehouseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, status from inventory_warehouse where id = ? and org_id = ?
                """, warehouseId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.warehouse.notFound");
        return rows.get(0);
    }

    private static final int PARAM = 310003;

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
