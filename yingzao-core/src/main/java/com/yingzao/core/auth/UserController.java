package com.yingzao.core.auth;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 组织用户查询(内核平台设施):审批转交/业务指派类场景的选择人数据源。
 * 仅本组织、仅 id 与显示名,不含凭据与联系方式。
 */
@RestController
public class UserController {

    private final JdbcTemplate jdbc;

    public UserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/users")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(jdbc.queryForList(
                "select id, display_name from yz_user where org_id = ? order by id", orgId));
    }
}
