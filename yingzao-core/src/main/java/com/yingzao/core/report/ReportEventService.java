package com.yingzao.core.report;

import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 看板埋点落库(单表 yz_report_event,5 类事件;服务编排不服务考核——埋点不进任何考核口径)。
 */
@Service
public class ReportEventService {

    public static final int CODE_BADREQUEST = 124006;

    private static final Set<String> TYPES = Set.of(
            "pageview", "dimension_switch", "card_exposure", "card_click", "dwell");

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public ReportEventService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public record EventCmd(String eventType, String metricKey, String payload) {
    }

    @Transactional
    public void record(long orgId, long userId, EventCmd cmd, String now) {
        if (cmd == null || cmd.eventType() == null || !TYPES.contains(cmd.eventType())) {
            throw new BizException(CODE_BADREQUEST,
                    messages.getMessage("report.event.badrequest", null, LocaleContextHolder.getLocale()));
        }
        String payload = cmd.payload();
        if (payload != null && payload.length() > 500) {
            payload = payload.substring(0, 500);
        }
        jdbc.update("""
                insert into yz_report_event (org_id, user_id, event_type, metric_key, payload, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, orgId, userId, cmd.eventType(), cmd.metricKey(), payload, now);
    }
}
