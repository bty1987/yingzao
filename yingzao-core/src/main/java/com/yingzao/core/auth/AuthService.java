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

import com.yingzao.core.web.BizException;

@Service
public class AuthService {

    public record LoginResult(String token, Long userId, Long orgId, String displayName) {
    }

    private final JdbcTemplate jdbc;
    private final TokenStore tokenStore;
    private final MessageSource messages;

    public AuthService(JdbcTemplate jdbc, TokenStore tokenStore, MessageSource messages) {
        this.jdbc = jdbc;
        this.tokenStore = tokenStore;
        this.messages = messages;
    }

    public LoginResult login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, password_sha256, display_name from yz_user where username = ? limit 1", username);
        if (rows.isEmpty() || !sha256(password).equalsIgnoreCase((String) rows.get(0).get("password_sha256"))) {
            throw new BizException(110001,
                    messages.getMessage("auth.login.failed", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> user = rows.get(0);
        Long userId = ((Number) user.get("id")).longValue();
        Long orgId = ((Number) user.get("org_id")).longValue();
        String displayName = (String) user.get("display_name");
        return new LoginResult(tokenStore.issue(userId, orgId), userId, orgId, displayName);
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
