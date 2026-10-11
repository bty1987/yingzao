package com.yingzao.core.rbac;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * RBAC 最小设施(内核平台设施):角色 CRUD + 用户绑定 + 菜单/功能点授权。
 * 身份口径:菜单 = addon.yml menus[].path,功能点 = mounts[].capability,授权只存身份字符串——
 * 菜单仍由插件动态下发,授权行不随插件启停漂移(修掉老系统 functionright 按公司复制整表的散装实现)。
 * 内置角色 admin(builtin=1)= 平台管理员,旁路全部过滤;其自身不可删、不可改授权。
 * 本设施只做「菜单/功能点可见性」门控,业务 API 的端点级鉴权为 v2 范围。
 */
@Service
public class RoleService {

    public static final int CODE_ROLE_NOT_FOUND = 122000;
    public static final int CODE_ROLE_BAD_REQUEST = 122001;
    public static final int CODE_ROLE_BUILTIN = 122002;
    public static final int CODE_ROLE_FORBIDDEN = 122003;
    public static final int CODE_ROLE_DUP = 122004;
    public static final int CODE_ROLE_USER_INVALID = 122005;

    public static final String ADMIN_CODE = "admin";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");

    public record CreateCmd(long orgId, String code, String name, String remark) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public RoleService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    /** 角色列表(本组织,含成员/菜单计数)。 */
    public List<Map<String, Object>> list(long orgId) {
        return jdbc.queryForList("""
                select r.id, r.code, r.name, r.builtin, r.remark, r.created_at,
                       (select count(*) from yz_user_role ur where ur.role_id = r.id) as user_count,
                       (select count(*) from yz_role_menu m where m.role_id = r.id) as menu_count,
                       (select count(*) from yz_role_capability c where c.role_id = r.id) as capability_count
                from yz_role r where r.org_id = ?
                order by r.id
                """, orgId);
    }

    @Transactional
    public long create(CreateCmd cmd) {
        if (cmd.code() == null || !CODE_PATTERN.matcher(cmd.code()).matches()
                || blank(cmd.name()) || cmd.name().length() > 64
                || (cmd.remark() != null && cmd.remark().length() > 200)) {
            throw badRequest();
        }
        Integer dup = jdbc.queryForObject(
                "select count(*) from yz_role where org_id = ? and code = ?",
                Integer.class, cmd.orgId(), cmd.code());
        if (dup != null && dup > 0) {
            throw new BizException(CODE_ROLE_DUP,
                    messages.getMessage("role.dup", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("insert into yz_role(org_id, code, name, builtin, remark) values (?,?,?,0,?)",
                cmd.orgId(), cmd.code(), cmd.name(), cmd.remark());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 角色详情:授权明细(菜单/功能点)+ 成员清单。 */
    public Map<String, Object> detail(long orgId, long id) {
        Map<String, Object> role = requireRole(orgId, id);
        role.put("menus", jdbc.queryForList(
                "select menu_path from yz_role_menu where role_id = ? order by menu_path", id)
                .stream().map(m -> m.get("menu_path")).toList());
        role.put("capabilities", jdbc.queryForList(
                "select capability from yz_role_capability where role_id = ? order by capability", id)
                .stream().map(c -> c.get("capability")).toList());
        role.put("users", jdbc.queryForList("""
                select u.id, u.display_name from yz_user_role ur join yz_user u on u.id = ur.user_id
                where ur.role_id = ? order by u.id
                """, id));
        return role;
    }

    @Transactional
    public void delete(long orgId, long id) {
        requireRole(orgId, id);
        requireNotBuiltin(id);
        jdbc.update("delete from yz_user_role where role_id = ?", id);
        jdbc.update("delete from yz_role_menu where role_id = ?", id);
        jdbc.update("delete from yz_role_capability where role_id = ?", id);
        jdbc.update("delete from yz_role where id = ?", id);
    }

    /** 菜单授权全量替换(传入集合即目标态,幂等)。 */
    @Transactional
    public void updateMenus(long orgId, long id, List<String> menus) {
        requireRole(orgId, id);
        requireNotBuiltin(id);
        Set<String> normalized = normalize(menus, 189);
        jdbc.update("delete from yz_role_menu where role_id = ?", id);
        for (String path : normalized) {
            jdbc.update("insert into yz_role_menu(role_id, menu_path) values (?,?)", id, path);
        }
    }

    /** 功能点授权全量替换。 */
    @Transactional
    public void updateCapabilities(long orgId, long id, List<String> capabilities) {
        requireRole(orgId, id);
        requireNotBuiltin(id);
        Set<String> normalized = normalize(capabilities, 100);
        jdbc.update("delete from yz_role_capability where role_id = ?", id);
        for (String capability : normalized) {
            jdbc.update("insert into yz_role_capability(role_id, capability) values (?,?)", id, capability);
        }
    }

    /** 成员绑定全量替换;成员必须存在且属于本组织。 */
    @Transactional
    public void updateUsers(long orgId, long id, List<Long> userIds) {
        requireRole(orgId, id);
        requireNotBuiltin(id);
        Set<Long> ids = normalizeIds(userIds);
        for (Long userId : ids) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from yz_user where id = ? and org_id = ?", Integer.class, userId, orgId);
            if (count == null || count == 0) {
                throw new BizException(CODE_ROLE_USER_INVALID,
                        messages.getMessage("role.user.invalid", null, LocaleContextHolder.getLocale()));
            }
        }
        jdbc.update("delete from yz_user_role where role_id = ?", id);
        for (Long userId : ids) {
            jdbc.update("insert into yz_user_role(user_id, role_id) values (?,?)", userId, id);
        }
    }

    /** 是否平台管理员(绑定内置 admin 角色)。 */
    public boolean isAdmin(long orgId, long userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from yz_user_role ur join yz_role r on r.id = ur.role_id
                where ur.user_id = ? and r.org_id = ? and r.builtin = 1 and r.code = ?
                """, Integer.class, userId, orgId, ADMIN_CODE);
        return count != null && count > 0;
    }

    /** 管理端点守卫:非管理员一律 122003。 */
    public void requireAdmin(long orgId, long userId) {
        if (!isAdmin(orgId, userId)) {
            throw new BizException(CODE_ROLE_FORBIDDEN,
                    messages.getMessage("role.forbidden", null, LocaleContextHolder.getLocale()));
        }
    }

    /** 登录回显用:用户全部角色编码。 */
    public List<String> roleCodes(long userId) {
        return jdbc.queryForList("""
                select r.code from yz_user_role ur join yz_role r on r.id = ur.role_id
                where ur.user_id = ? order by r.id
                """, userId).stream().map(r -> String.valueOf(r.get("code"))).toList();
    }

    /** 可见菜单 = 用户全部角色的菜单授权并集(管理员旁路由 AddonController 判断)。 */
    public Set<String> grantedMenus(long userId) {
        return Set.copyOf(jdbc.queryForList(
                "select distinct m.menu_path from yz_role_menu m join yz_user_role ur on ur.role_id = m.role_id"
                        + " where ur.user_id = ?",
                userId).stream().map(r -> String.valueOf(r.get("menu_path"))).toList());
    }

    /** 可见功能点 = 用户全部角色的功能点授权并集。 */
    public Set<String> grantedCapabilities(long userId) {
        return Set.copyOf(jdbc.queryForList(
                "select distinct c.capability from yz_role_capability c join yz_user_role ur on ur.role_id = c.role_id"
                        + " where ur.user_id = ?",
                userId).stream().map(r -> String.valueOf(r.get("capability"))).toList());
    }

    private Map<String, Object> requireRole(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, code, name, builtin, remark, created_at from yz_role where id = ? and org_id = ?",
                id, orgId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    private void requireNotBuiltin(long id) {
        Integer builtin = jdbc.queryForObject("select builtin from yz_role where id = ?", Integer.class, id);
        if (builtin != null && builtin == 1) {
            throw new BizException(CODE_ROLE_BUILTIN,
                    messages.getMessage("role.builtin", null, LocaleContextHolder.getLocale()));
        }
    }

    private Set<String> normalize(List<String> values, int maxLength) {
        if (values == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (blank(value) || value.length() > maxLength) {
                throw badRequest();
            }
            result.add(value.trim());
        }
        return result;
    }

    private Set<Long> normalizeIds(List<Long> values) {
        if (values == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw badRequest();
            }
            result.add(value);
        }
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BizException notFound() {
        return new BizException(CODE_ROLE_NOT_FOUND,
                messages.getMessage("role.notfound", null, LocaleContextHolder.getLocale()));
    }

    private BizException badRequest() {
        return new BizException(CODE_ROLE_BAD_REQUEST,
                messages.getMessage("role.badrequest", null, LocaleContextHolder.getLocale()));
    }
}
