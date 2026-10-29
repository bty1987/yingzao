package com.yingzao.addon.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

/**
 * 驾驶舱端点(菜单闸 /api/dashboard require:/dashboard):
 * GET home = 聚合首页(结论条+六维健康卡+双线+大区排名);
 * home-config = 按角色默认落地页最小配置(PRD 开放问题 6:V1 admin 可配,聚合首页缺省)。
 */
@RestController
public class DashboardController {

    public static final int CODE_BADREQUEST = 390001;
    public static final int CODE_FORBIDDEN = 390002;

    private final DashboardService service;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final MessageSource messages;

    public DashboardController(DashboardService service, JdbcTemplate jdbc, RoleService roles,
            MessageSource messages) {
        this.service = service;
        this.jdbc = jdbc;
        this.roles = roles;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }

    private void requireAdmin(long orgId, long userId) {
        require(roles.grantsCached(orgId, userId).admin(), CODE_FORBIDDEN, "dashboard.forbidden");
    }

    @GetMapping("/api/dashboard/home")
    public ApiResponse<Map<String, Object>> home(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(service.home(orgId));
    }

    // ---- 按角色默认落地页(V1 最小配置) ----

    public record HomeConfigCmd(Long roleId, String homePath) {
    }

    @GetMapping("/api/dashboard/home-config")
    public ApiResponse<List<Map<String, Object>>> homeConfig(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(jdbc.queryForList("""
                select r.id as role_id, r.name as role_name, h.home_path
                from yz_role r
                left join dashboard_role_home h on h.role_id = r.id and h.org_id = r.org_id
                where r.org_id = ? order by r.id
                """, orgId));
    }

    @PostMapping("/api/dashboard/home-config")
    @Transactional
    public ApiResponse<Void> saveHomeConfig(@RequestBody HomeConfigCmd cmd, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        requireAdmin(orgId, userId);
        require(cmd != null && cmd.roleId() != null && cmd.roleId() > 0
                && cmd.homePath() != null && cmd.homePath().startsWith("/"),
                CODE_BADREQUEST, "dashboard.badconfig");
        Integer roleExists = jdbc.queryForObject(
                "select count(*) from yz_role where id = ? and org_id = ?",
                Integer.class, cmd.roleId(), orgId);
        require(roleExists != null && roleExists > 0, CODE_BADREQUEST, "dashboard.badconfig");
        jdbc.update("""
                insert into dashboard_role_home (org_id, role_id, home_path, updated_by)
                values (?, ?, ?, ?)
                on duplicate key update home_path = values(home_path), updated_by = values(updated_by)
                """, orgId, cmd.roleId(), cmd.homePath(), userId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/api/dashboard/home-config/{roleId}")
    @Transactional
    public ApiResponse<Void> deleteHomeConfig(@PathVariable long roleId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        requireAdmin(orgId, userId);
        jdbc.update("delete from dashboard_role_home where org_id = ? and role_id = ?", orgId, roleId);
        return ApiResponse.ok();
    }
}
