package com.yingzao.addon.member;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.ActivityStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 会员活动主档服务(积分商城心脏:倍率/有效期/适用域唯一来源)。
 * 修老三哑弹:①VO 时间 String 被 BeanUtil 静默吞 → start/end 恒 null → 建档必填解析失败即拒;
 * ②validays 不拷贝恒 0 → 可用装修积分恒 0 → 建档必填 >0;
 * ③时间窗形同虚设(queryActivityForValid 不看时间)→ 记分/记分查询一律带时间窗真生效。
 * 默认活动:每 org+type 至多一个(is_default 可空唯一列),切换=事务先清后置、旧默认自动让位
 * (修老 defaults 原生 SQL 全表重置);停用默认活动允许(修老 160047 硬禁),记分查询过滤 status=0,
 * 重新启用即恢复——二次确认是前端交互,后端不拦。
 */
@Service
public class MemberActivityService {

    public static final int CODE_NOT_FOUND = 350001;
    public static final int CODE_PARAM = 350005;
    /** 🔴 350012 已被兑换域占用(exchange.revoke),活动状态错误用 350021——同码同消息军规。 */
    public static final int CODE_STATUS = 350021;

    public static final int TYPE_DECORATION = 1;
    public static final int TYPE_MATERIAL = 2;
    public static final int TYPE_SALES = 3;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;

    public MemberActivityService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
    }

    /** 建档命令:时间窗字符串 yyyy-MM-dd HH:mm:ss。 */
    public record CreateCmd(String name, Integer type, Integer multiple, Integer validays,
            String startTime, String endTime, Boolean isDefault, String remark) {
    }

    /** 编辑命令(白名单:name/multiple/validays/时间窗/remark;type 适用域语义全变,建档后不可改)。 */
    public record UpdateCmd(String name, Integer multiple, Integer validays,
            String startTime, String endTime, String remark) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, Long userId) {
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_PARAM, "activity.param");
        require(cmd.type() != null && (cmd.type() == TYPE_DECORATION || cmd.type() == TYPE_MATERIAL
                || cmd.type() == TYPE_SALES), CODE_PARAM, "activity.param");
        int multiple = cmd.multiple() == null ? 1000 : cmd.multiple();
        require(multiple > 0, CODE_PARAM, "activity.param");
        require(cmd.validays() != null && cmd.validays() > 0, CODE_PARAM, "activity.param");
        LocalDateTime start = parseTime(cmd.startTime());
        LocalDateTime end = parseTime(cmd.endTime());
        require(start != null && end != null && end.isAfter(start), CODE_PARAM, "activity.param");
        jdbc.update("""
                insert into member_activity
                    (org_id, name, type, multiple, validays, start_time, end_time, status, remark, created_by)
                values (?,?,?,?,?,?,?,0,?,?)
                """, orgId, cmd.name().trim(), cmd.type(), multiple, cmd.validays(),
                start, end, cmd.remark(), userId);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        if (Boolean.TRUE.equals(cmd.isDefault())) {
            // 先插 null 再切换默认(事务先清后置):insert 直接带 1 会与既有默认撞 uk(org,type,is_default)
            switchDefault(orgId, id, cmd.type(), userId);
        }
        return id;
    }

    /** 编辑(白名单,null=保留现值,修老 updateActivity 全字段覆盖)。 */
    @Transactional
    public void update(long orgId, long id, UpdateCmd cmd) {
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "activity.notfound");
        LocalDateTime start = parseTime(cmd.startTime());
        LocalDateTime end = parseTime(cmd.endTime());
        if (cmd.multiple() != null) {
            require(cmd.multiple() > 0, CODE_PARAM, "activity.param");
        }
        if (cmd.validays() != null) {
            require(cmd.validays() > 0, CODE_PARAM, "activity.param");
        }
        if (start != null && end == null) {
            end = toLocalDateTime(row.get("end_time"));
        }
        if (end != null && start == null) {
            start = toLocalDateTime(row.get("start_time"));
        }
        if (start != null && end != null) {
            require(end.isAfter(start), CODE_PARAM, "activity.param");
        }
        jdbc.update("""
                update member_activity set
                    name = ?, multiple = ?, validays = ?, start_time = ?, end_time = ?, remark = ?
                where id = ? and org_id = ?
                """,
                firstNonBlank(cmd.name(), (String) row.get("name")),
                cmd.multiple() != null ? cmd.multiple() : ((Number) row.get("multiple")).intValue(),
                cmd.validays() != null ? cmd.validays() : ((Number) row.get("validays")).intValue(),
                start != null ? start : toLocalDateTime(row.get("start_time")),
                end != null ? end : toLocalDateTime(row.get("end_time")),
                cmd.remark() != null ? cmd.remark() : (String) row.get("remark"),
                id, orgId);
    }

    /** 启停(可逆;停用默认活动允许,记分查询过滤 status=0;同态重放幂等零重复事件)。 */
    @Transactional
    public void changeStatus(long orgId, long id, int status, Long userId) {
        require(status == 0 || status == 1, CODE_PARAM, "activity.param");
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "activity.notfound");
        int type = ((Number) row.get("type")).intValue();
        if (((Number) row.get("status")).intValue() == status) {
            return;
        }
        jdbc.update("update member_activity set status = ? where id = ? and org_id = ?", status, id, orgId);
        String action = status == 0 ? "enable" : "disable";
        bus.publish(new ActivityStatusChangedEvent(id, orgId, type, action, userId),
                "member-activity:" + id + ":" + action);
    }

    /** 设默认(事务先清后置,新默认顶旧默认;已默认幂等短路)。 */
    @Transactional
    public void setDefault(long orgId, long id, Long userId) {
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "activity.notfound");
        require(((Number) row.get("status")).intValue() == 0, CODE_STATUS, "activity.status");
        int type = ((Number) row.get("type")).intValue();
        if (row.get("is_default") != null) {
            return;
        }
        // 记录旧默认(事件留痕),清旧置新
        List<Long> oldIds = jdbc.queryForList(
                "select id from member_activity where org_id = ? and type = ? and is_default = 1",
                Long.class, orgId, type);
        jdbc.update("update member_activity set is_default = null where org_id = ? and type = ? and is_default = 1",
                orgId, type);
        jdbc.update("update member_activity set is_default = 1 where id = ? and org_id = ?", id, orgId);
        for (Long oldId : oldIds) {
            bus.publish(new ActivityStatusChangedEvent(oldId, orgId, type, "loseDefault", userId),
                    "member-activity:" + oldId + ":loseDefault");
        }
        bus.publish(new ActivityStatusChangedEvent(id, orgId, type, "setDefault", userId),
                "member-activity:" + id + ":setDefault");
    }

    /** 记分用:该组织该域当前真生效的默认活动(status=0 + 时间窗内;时间窗真生效,修老形同虚设)。 */
    public Map<String, Object> activeDefault(long orgId, int type) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, multiple, validays from member_activity
                where org_id = ? and type = ? and is_default = 1 and status = 0
                  and start_time <= now() and end_time >= now()
                order by id limit 1
                """, orgId, type);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> get(long orgId, long id) {
        Map<String, Object> row = find(orgId, id);
        require(row != null, CODE_NOT_FOUND, "activity.notfound");
        return row;
    }

    public Map<String, Object> page(long orgId, Integer type, Integer status, String keyword, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and name like concat('%', ?, '%')");
            args.add(keyword.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from member_activity" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, name, type, multiple, validays, start_time, end_time, is_default, status,"
                + " remark, created_at, updated_at from member_activity" + where
                + " order by is_default desc, id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 内部 ----

    private void switchDefault(long orgId, long id, int type, Long userId) {
        List<Long> oldIds = jdbc.queryForList(
                "select id from member_activity where org_id = ? and type = ? and is_default = 1 and id <> ?",
                Long.class, orgId, type, id);
        jdbc.update("update member_activity set is_default = null where org_id = ? and type = ? and is_default = 1 and id <> ?",
                orgId, type, id);
        jdbc.update("update member_activity set is_default = 1 where id = ?", id);
        for (Long oldId : oldIds) {
            bus.publish(new ActivityStatusChangedEvent(oldId, orgId, type, "loseDefault", userId),
                    "member-activity:" + oldId + ":loseDefault");
        }
        bus.publish(new ActivityStatusChangedEvent(id, orgId, type, "setDefault", userId),
                "member-activity:" + id + ":setDefault");
    }

    private Map<String, Object> find(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, name, type, multiple, validays, start_time, end_time, is_default, status,"
                + " remark, created_at, updated_at from member_activity where id = ? and org_id = ?", id, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), FMT);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(text.trim()); // ISO(yyyy-MM-ddTHH:mm:ss)
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        return null;
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
