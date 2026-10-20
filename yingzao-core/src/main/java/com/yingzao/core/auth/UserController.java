package com.yingzao.core.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 组织用户管理(内核平台设施):选择人数据源(既有,仅启用)+ 管理面(建号/启停/重置密码)。
 * 管理端点仅管理员(复用 RoleService.requireAdmin 122003;内核端点自带守卫,RBAC 豁免同 roles/plans)。
 * 停用与重置密码即时生效:TokenStore.evictUser 踢出该账号全部在管会话(修「停用后旧令牌 12h 内仍有效」)。
 * 错误码段 120100-120105(内核 100000-199999;120001 登录失败/110002 未登录既有)。
 */
@RestController
public class UserController {

    public static final int CODE_DUP = 120101;
    public static final int CODE_DISABLED = 120102;
    public static final int CODE_WEAK_PASSWORD = 120103;
    public static final int CODE_SELF_DISABLE = 120104;
    public static final int CODE_NOT_FOUND = 120105;

    private final JdbcTemplate jdbc;
    private final RoleService roleService;
    private final TokenStore tokenStore;
    private final org.springframework.context.MessageSource messages;

    public UserController(JdbcTemplate jdbc, RoleService roleService, TokenStore tokenStore,
            org.springframework.context.MessageSource messages) {
        this.jdbc = jdbc;
        this.roleService = roleService;
        this.tokenStore = tokenStore;
        this.messages = messages;
    }

    /** 选择人数据源(审批转交/业务指派):仅本组织、仅启用、仅 id 与显示名。 */
    @GetMapping("/api/users")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(jdbc.queryForList(
                "select id, display_name from yz_user where org_id = ? and status = 0 order by id", orgId));
    }

    public record CreateBody(String username, String displayName, String password, List<Long> roleIds) {
    }

    /** 建号:用户名全局唯一(uk 兜底),初始密码 sha256;可选同组织角色绑定。 */
    @PostMapping("/api/users")
    public ApiResponse<Long> create(@RequestBody CreateBody body, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        String username = body == null || body.username() == null ? "" : body.username().trim();
        String displayName = body == null || body.displayName() == null ? "" : body.displayName().trim();
        String password = body == null ? null : body.password();
        require(!username.isBlank() && username.length() <= 64, CODE_WEAK_PASSWORD, "auth.user.weakPassword");
        require(!displayName.isBlank() && displayName.length() <= 64, CODE_WEAK_PASSWORD, "auth.user.weakPassword");
        require(password != null && password.length() >= 6, CODE_WEAK_PASSWORD, "auth.user.weakPassword");
        Integer dup = jdbc.queryForObject(
                "select count(*) from yz_user where username = ?", Integer.class, username);
        require(dup == null || dup == 0, CODE_DUP, "auth.user.dup");
        jdbc.update("""
                insert into yz_user(org_id, username, password_sha256, display_name, status)
                values (?,?,?,?,0)
                """, orgId, username, AuthService.sha256(password), displayName);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        bindRoles(orgId, id, body == null ? null : body.roleIds());
        return ApiResponse.ok(id);
    }

    /** 管理分页:关键字(用户名/显示名)+状态过滤,附角色名聚合。 */
    @GetMapping("/api/users/manage")
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where u.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (u.username like ? or u.display_name like ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (status != null) {
            where.append(" and u.status = ?");
            args.add(status);
        }
        String w = where.toString();
        Long total = jdbc.queryForObject("select count(*) from yz_user u" + w, Long.class, args.toArray());
        List<Object> all = new ArrayList<>(args);
        all.add(safeSize);
        all.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.id, u.username, u.display_name, u.status, u.created_at,
                       coalesce((select group_concat(r.name order by r.id)
                                 from yz_user_role ur join yz_role r on r.id = ur.role_id
                                 where ur.user_id = u.id), '') as roles
                from yz_user u""" + w + " order by u.id limit ? offset ?", all.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return ApiResponse.ok(result);
    }

    public record StatusBody(Integer status) {
    }

    /** 启用/停用:停自己拒(防锁死);停用即时踢会话。 */
    @PostMapping("/api/users/{id}/status")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        int status = body == null || body.status() == null ? 0 : body.status();
        require(status == 0 || status == 1, CODE_WEAK_PASSWORD, "auth.user.weakPassword");
        require(!(status == 1 && id == userId), CODE_SELF_DISABLE, "auth.user.selfDisable");
        require(exists(orgId, id), CODE_NOT_FOUND, "auth.user.notFound");
        jdbc.update("update yz_user set status = ? where id = ? and org_id = ?", status, id, orgId);
        if (status == 1) {
            tokenStore.evictUser(id);
        }
        return ApiResponse.ok();
    }

    public record ResetPasswordBody(String password) {
    }

    /** 重置密码:更新即踢会话(旧令牌全部失效,重发需重新登录)。 */
    @PostMapping("/api/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable long id, @RequestBody ResetPasswordBody body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, userId);
        String password = body == null ? null : body.password();
        require(password != null && password.length() >= 6, CODE_WEAK_PASSWORD, "auth.user.weakPassword");
        require(exists(orgId, id), CODE_NOT_FOUND, "auth.user.notFound");
        jdbc.update("update yz_user set password_sha256 = ? where id = ? and org_id = ?",
                AuthService.sha256(password), id, orgId);
        tokenStore.evictUser(id);
        return ApiResponse.ok();
    }

    // ---- 内部 ----

    private boolean exists(long orgId, long id) {
        Integer n = jdbc.queryForObject(
                "select count(*) from yz_user where id = ? and org_id = ?", Integer.class, id, orgId);
        return n != null && n > 0;
    }

    /** 角色绑定:仅接受同组织角色(insert..select 过滤),roleIds 空跳过。 */
    private void bindRoles(long orgId, long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds) {
            if (roleId == null || roleId <= 0) {
                continue;
            }
            jdbc.update("""
                    insert ignore into yz_user_role(user_id, role_id)
                    select ?, id from yz_role where id = ? and org_id = ?
                    """, userId, roleId, orgId);
        }
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, messages.getMessage(key, null, org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
    }
}
