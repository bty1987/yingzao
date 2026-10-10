package com.yingzao.core.notification;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.yingzao.core.web.BizException;

/**
 * 站内通知(内核平台设施):任何插件监听到领域事件后调用 send 落通知;
 * 内核不订阅、不解释任何业务事件——业务语义全部留在插件侧。
 */
@Service
public class NotificationService {

    public static final int CODE_NOTIFICATION_NOT_FOUND = 120001;

    public record SendCmd(long orgId, long userId, String title, String content,
                          String sourceAddon, String sourceType, String sourceId) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public NotificationService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public long send(SendCmd cmd) {
        jdbc.update("""
                insert into yz_notification(org_id, user_id, title, content, source_addon, source_type, source_id)
                values (?,?,?,?,?,?,?)
                """, cmd.orgId(), cmd.userId(), cmd.title(), cmd.content(),
                cmd.sourceAddon(), cmd.sourceType(), cmd.sourceId());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    public List<Map<String, Object>> list(long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String sql = """
                select id, title, content, source_addon, source_type, source_id, read_flag, created_at
                from yz_notification
                where user_id = ? %s
                order by id desc
                limit ? offset ?
                """.formatted(unreadOnly ? "and read_flag = 0" : "");
        return jdbc.queryForList(sql, userId, safeSize, (safePage - 1) * safeSize);
    }

    public long unreadCount(long userId) {
        Long count = jdbc.queryForObject(
                "select count(*) from yz_notification where user_id = ? and read_flag = 0", Long.class, userId);
        return count == null ? 0 : count;
    }

    public void markRead(long userId, long notificationId) {
        requireOwned(userId, notificationId);
        jdbc.update("update yz_notification set read_flag = 1 where id = ? and user_id = ?", notificationId, userId);
    }

    public void markAllRead(long userId) {
        jdbc.update("update yz_notification set read_flag = 1 where user_id = ? and read_flag = 0", userId);
    }

    private void requireOwned(long userId, long notificationId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from yz_notification where id = ? and user_id = ?",
                Integer.class, notificationId, userId);
        if (count == null || count == 0) {
            throw new BizException(CODE_NOTIFICATION_NOT_FOUND,
                    messages.getMessage("notification.notfound", null, LocaleContextHolder.getLocale()));
        }
    }
}
