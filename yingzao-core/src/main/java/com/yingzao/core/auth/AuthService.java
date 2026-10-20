package com.yingzao.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.BizException;

@Service
public class AuthService {

    /** roles = 用户全部角色编码(RBAC);前端据此决定管理类入口可见性,服务端守卫才是真闸门。 */
    public record LoginResult(String token, Long userId, Long orgId, String displayName, List<String> roles) {
    }

    private final JdbcTemplate jdbc;
    private final TokenStore tokenStore;
    private final MessageSource messages;
    private final RoleService roleService;

    public AuthService(JdbcTemplate jdbc, TokenStore tokenStore, MessageSource messages, RoleService roleService) {
        this.jdbc = jdbc;
        this.tokenStore = tokenStore;
        this.messages = messages;
        this.roleService = roleService;
    }

    public LoginResult login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, password_sha256, display_name, status from yz_user where username = ? limit 1", username);
        if (rows.isEmpty() || !sha256(password).equalsIgnoreCase((String) rows.get(0).get("password_sha256"))) {
            throw new BizException(110001,
                    messages.getMessage("auth.login.failed", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> user = rows.get(0);
        if (((Number) user.get("status")).intValue() != 0) {
            // 账号已停用:单独提示(内部系统,不涉账号存在性探测);停用时会话已被 evictUser 踢出
            throw new BizException(120102,
                    messages.getMessage("auth.login.disabled", null, LocaleContextHolder.getLocale()));
        }
        Long userId = ((Number) user.get("id")).longValue();
        Long orgId = ((Number) user.get("org_id")).longValue();
        String displayName = (String) user.get("display_name");
        return new LoginResult(tokenStore.issue(userId, orgId), userId, orgId, displayName,
                roleService.roleCodes(userId));
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
