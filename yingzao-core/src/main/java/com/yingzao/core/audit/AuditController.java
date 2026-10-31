package com.yingzao.core.audit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计日志查询(内核平台设施,F3):操作/登录两查询面(docs/15「Web 查询两页」的服务端)。
 * 端点自带 admin 守卫(复用 requireAdmin 122003,内核端点豁免 RBAC 菜单闸,同 users/plans);
 * 查询端点自身不挂 @AuditLog(查询零噪声)。错误码零新增。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final JdbcTemplate jdbc;
    private final RoleService roleService;

    public AuditController(JdbcTemplate jdbc, RoleService roleService) {
        this.jdbc = jdbc;
        this.roleService = roleService;
    }

    /**
     * 操作日志分页:keyword 模糊(user_name/action/module/object_type/object_id/summary),
     * userId 精确,begin/end 日期闭区间(yyyy-MM-dd)。
     */
    @GetMapping("/operations")
    public ApiResponse<Map<String, Object>> operations(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String begin, @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long viewerId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, viewerId);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where category = 0 and org_id = ?");
        args.add(orgId);
        appendKeyword(where, args, keyword, new String[] {"user_name", "action", "module", "object_type", "object_id", "summary"});
        if (userId != null && userId > 0) {
            where.append(" and user_id = ?");
            args.add(userId);
        }
        appendRange(where, args, begin, end);
        String w = where.toString();
        Long total = jdbc.queryForObject("select count(*) from yz_audit_log" + w, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.min(Math.max(size, 1), 100));
        pageArgs.add((Math.max(page, 1) - 1) * Math.min(Math.max(size, 1), 100));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, user_id, user_name, action, module, object_type, object_id, summary, "
                        + "detail, ip, result, error_code, error_msg, run_time, created_at from yz_audit_log"
                        + w + " order by id desc limit ? offset ?", pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return ApiResponse.ok(result);
    }

    /**
     * 登录日志分页:keyword 模糊(user_name/action),result 过滤(0 成功/1 失败;evict 行 result=0
     * 但 action=evict,可用 keyword=evict 单查),begin/end 同上。
     */
    @GetMapping("/logins")
    public ApiResponse<Map<String, Object>> logins(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String begin, @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long viewerId = (Long) request.getAttribute("yz.userId");
        roleService.requireAdmin(orgId, viewerId);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where category = 1 and (org_id = ? or org_id = 0)");
        args.add(orgId);
        appendKeyword(where, args, keyword, new String[] {"user_name", "action", "summary"});
        if (result != null) {
            where.append(" and result = ?");
            args.add(result);
        }
        appendRange(where, args, begin, end);
        String w = where.toString();
        Long total = jdbc.queryForObject("select count(*) from yz_audit_log" + w, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.min(Math.max(size, 1), 100));
        pageArgs.add((Math.max(page, 1) - 1) * Math.min(Math.max(size, 1), 100));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, user_id, user_name, action, module, summary, ip, result, error_code, "
                        + "error_msg, created_at from yz_audit_log" + w + " order by id desc limit ? offset ?",
                pageArgs.toArray());
        Map<String, Object> resultBody = new LinkedHashMap<>();
        resultBody.put("total", total == null ? 0 : total);
        resultBody.put("rows", rows);
        return ApiResponse.ok(resultBody);
    }

    // ---- 内部 ----

    private void appendKeyword(StringBuilder where, List<Object> args, String keyword, String[] columns) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        where.append(" and (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                where.append(" or ");
            }
            where.append(columns[i]).append(" like ?");
            args.add("%" + keyword.trim() + "%");
        }
        where.append(")");
    }

    private void appendRange(StringBuilder where, List<Object> args, String begin, String end) {
        // 日期格式非法=按未传处理(审计查询组件纪律:自身永不因脏参数失败;docs/rules/audit.md §3)
        try {
            if (begin != null && !begin.isBlank()) {
                LocalDateTime b = LocalDate.parse(begin.trim()).atStartOfDay();
                where.append(" and created_at >= ?");
                args.add(b);
            }
            if (end != null && !end.isBlank()) {
                LocalDateTime e = LocalDate.parse(end.trim()).atTime(23, 59, 59);
                where.append(" and created_at <= ?");
                args.add(e);
            }
        } catch (Exception ignored) {
            // fall through: 忽略非法日期区间
        }
    }
}
