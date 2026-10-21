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
    private final com.yingzao.core.org.OrganizationService organizations;

    public AuthService(JdbcTemplate jdbc, TokenStore tokenStore, MessageSource messages,
            RoleService roleService, com.yingzao.core.org.OrganizationService organizations) {
        this.jdbc = jdbc;
        this.tokenStore = tokenStore;
        this.messages = messages;
        this.roleService = roleService;
        this.organizations = organizations;
    }

    public LoginResult login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.id, u.org_id, u.password_sha256, u.display_name, u.status,
                       o.status as org_status
                from yz_user u left join organization o on o.id = u.org_id
                where u.username = ? limit 1
                """, username);
        if (rows.isEmpty() || !sha256(password).equalsIgnoreCase((String) rows.get(0).get("password_sha256"))) {
            throw new BizException(110001,
                    messages.getMessage("auth.login.failed", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> user = rows.get(0);
        Object orgStatus = user.get("org_status");
        if (orgStatus != null && ((Number) orgStatus).intValue() != 0) {
            // 所属组织已停用(docs/08 S1;组织停用=子树级联,登录即拒;旧会话由组织停用方踢出)
            throw new BizException(120106,
                    messages.getMessage("auth.login.orgDisabled", null, LocaleContextHolder.getLocale()));
        }
        if (((Number) user.get("status")).intValue() != 0) {
            // 账号已停用:单独提示(内部系统,不涉账号存在性探测);停用时会话已被 evictUser 踢出
            throw new BizException(120102,
                    messages.getMessage("auth.login.disabled", null, LocaleContextHolder.getLocale()));
        }
        Long userId = ((Number) user.get("id")).longValue();
        Long orgId = ((Number) user.get("org_id")).longValue();
        organizations.rowOf(orgId); // 历史 org 首次触达懒建根节点(docs/08 零迁移)
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
