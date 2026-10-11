package com.yingzao.addon.construction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 施工团队留痕:工人指派(未开工节点拒,修老 120026 口径)→ 评分三率(状态机 0→2→3,
 * 修老批量评分后门无守卫)→ 进出场流水 append-only 正序校验(修老 enter/leave 守卫写反)
 * → 施工日志(首笔自动判进场、次笔出场、之后普通日志,灭老「workStatus 不匹配静默 return」)。
 * 归属校验:一切操作先验 org,修老按主键裸删/越权回写。
 */
@Service
public class ConstructionTeamService {

    // ---- 工人状态:0 初始/1 已停用/2 已发送评分/3 已评分 ----
    public static final int W_INIT = 0;
    public static final int W_DISABLED = 1;
    public static final int W_SENT = 2;
    public static final int W_RATED = 3;
    /** 流水动作/日志类型。 */
    public static final int ACTION_ENTER = 1;
    public static final int ACTION_LEAVE = 2;
    public static final int DIARY_PLAIN = 0;

    private record WorkerRow(long id, long orderId, long scheduleId, long userId, int status) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSourceAdapter m;

    public ConstructionTeamService(JdbcTemplate jdbc, MessageSourceAdapter m) {
        this.jdbc = jdbc;
        this.m = m;
    }

    // ---- 工人指派与评分 ----

    @Transactional
    public long addWorker(long orgId, long scheduleId, long userId, long operatorId) {
        Map<String, Object> schedule = loadSchedule(orgId, scheduleId);
        int status = ((Number) schedule.get("status")).intValue();
        if (status == ConstructionService.SC_INIT) {
            throw new BizException(ConstructionService.CODE_WORKER_BEFORE_BEGIN,
                    m.msg("construction.worker.beforeBegin"));
        }
        requireUser(userId);
        Integer dup = jdbc.queryForObject("""
                select count(*) from construction_worker
                where org_id = ? and order_id = ? and schedule_id = ? and user_id = ?
                """, Integer.class, orgId, schedule.get("order_id"), scheduleId, userId);
        if (dup != null && dup > 0) {
            throw new BizException(ConstructionService.CODE_WORKER_DUP, m.msg("construction.worker.dup"));
        }
        jdbc.update("""
                insert into construction_worker(org_id, order_id, schedule_id, user_id, user_name)
                values (?,?,?,?,?)
                """, orgId, schedule.get("order_id"), scheduleId, userId, displayName(userId));
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    public List<Map<String, Object>> listWorkers(long orgId, long scheduleId) {
        loadSchedule(orgId, scheduleId);
        return jdbc.queryForList("""
                select id, user_id, user_name, status, work_status, score1, score2, score3, assessment, created_at
                from construction_worker where org_id = ? and schedule_id = ? order by id
                """, orgId, scheduleId);
    }

    /** 停用(软删 → 1):仅初始态(评分已发/已评分留档;工费守卫随 salary 域补)。 */
    @Transactional
    public void disableWorker(long orgId, long workerId) {
        WorkerRow worker = loadWorker(orgId, workerId);
        require(worker.status() == W_INIT, ConstructionService.CODE_BAD_STATE,
                m.msg("construction.state.illegal"));
        jdbc.update("update construction_worker set status = ? where id = ? and status = ?",
                W_DISABLED, workerId, W_INIT);
    }

    /** 发送评分(0→2):生成评分令牌(移动端评分页 V2 直达凭据)。 */
    @Transactional
    public String sendRateLink(long orgId, long workerId) {
        WorkerRow worker = loadWorker(orgId, workerId);
        require(worker.status() == W_INIT, ConstructionService.CODE_BAD_STATE,
                m.msg("construction.state.illegal"));
        String token = UUID.randomUUID().toString();
        jdbc.update("update construction_worker set status = ?, rate_token = ? where id = ? and status = ?",
                W_SENT, token, workerId, W_INIT);
        return token;
    }

    /** 评分(2→3):三率 1-5 星(老 21 固定列收为三率+评语,评分页客户端 V2)。 */
    @Transactional
    public void rate(long orgId, long workerId, Integer score1, Integer score2, Integer score3,
            String assessment) {
        WorkerRow worker = loadWorker(orgId, workerId);
        require(worker.status() == W_SENT, ConstructionService.CODE_BAD_STATE,
                m.msg("construction.state.illegal"));
        require(inStars(score1) && inStars(score2) && inStars(score3),
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        jdbc.update("""
                update construction_worker set status = ?, score1 = ?, score2 = ?, score3 = ?, assessment = ?
                where id = ? and status = ?
                """, W_RATED, score1, score2, score3, blankToNull(assessment), workerId, W_SENT);
    }

    // ---- 进出场流水(append-only;enter/leave 正序) ----

    @Transactional
    public void enter(long orgId, long workerId, long operatorId) {
        WorkerRow worker = loadWorker(orgId, workerId);
        require(worker.status() != W_DISABLED, ConstructionService.CODE_BAD_STATE,
                m.msg("construction.state.illegal"));
        require(lastAction(worker.id()) != ACTION_ENTER, ConstructionService.CODE_RECORD_ORDER,
                m.msg("construction.record.order"));
        insertRecord(orgId, worker, ACTION_ENTER, operatorId);
        jdbc.update("update construction_worker set work_status = ? where id = ?", ACTION_ENTER, workerId);
    }

    @Transactional
    public void leave(long orgId, long workerId, long operatorId) {
        WorkerRow worker = loadWorker(orgId, workerId);
        require(lastAction(worker.id()) == ACTION_ENTER, ConstructionService.CODE_RECORD_ORDER,
                m.msg("construction.record.order"));
        insertRecord(orgId, worker, ACTION_LEAVE, operatorId);
        jdbc.update("update construction_worker set work_status = ? where id = ?", ACTION_LEAVE, workerId);
    }

    public List<Map<String, Object>> listRecords(long orgId, long workerId) {
        loadWorker(orgId, workerId);
        return jdbc.queryForList("""
                select action, operator_name, created_at from construction_worker_record
                where worker_id = ? order by id
                """, workerId);
    }

    // ---- 施工日志(自动进出场判定) ----

    @Transactional
    public long addDiary(long orgId, long scheduleId, String content, Boolean hasDrawing, long operatorId) {
        Map<String, Object> schedule = loadSchedule(orgId, scheduleId);
        require(content != null && !content.isBlank() && content.trim().length() <= 500,
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        int workStatus = ((Number) schedule.get("work_status")).intValue();
        int atttype;
        if (workStatus == 0) {
            atttype = ACTION_ENTER;
            jdbc.update("update construction_schedule set work_status = ? where id = ?", ACTION_ENTER,
                    scheduleId);
        } else if (workStatus == ACTION_ENTER) {
            atttype = ACTION_LEAVE;
            jdbc.update("update construction_schedule set work_status = ? where id = ?", ACTION_LEAVE,
                    scheduleId);
        } else {
            atttype = DIARY_PLAIN;
        }
        jdbc.update("""
                insert into construction_diary(org_id, order_id, schedule_id, user_id, user_name, content,
                    atttype, has_drawing)
                values (?,?,?,?,?,?,?,?)
                """, orgId, schedule.get("order_id"), scheduleId, operatorId, displayName(operatorId),
                content.trim(), atttype, Boolean.TRUE.equals(hasDrawing) ? 1 : 0);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    public List<Map<String, Object>> listDiaries(long orgId, long scheduleId) {
        loadSchedule(orgId, scheduleId);
        return jdbc.queryForList("""
                select id, user_name, content, atttype, has_drawing, created_at
                from construction_diary where org_id = ? and schedule_id = ? order by id desc
                """, orgId, scheduleId);
    }

    public List<Map<String, Object>> listOrderDiaries(long orgId, long orderId) {
        return jdbc.queryForList("""
                select d.id, d.schedule_id, s.node_name, d.user_name, d.content, d.atttype, d.has_drawing,
                       d.created_at
                from construction_diary d join construction_schedule s on s.id = d.schedule_id
                where d.org_id = ? and d.order_id = ? order by d.id desc
                """, orgId, orderId);
    }

    // ---- 内部 ----

    /** cond 不满足即抛;第三个参数为已解析文案(调用方用 m.msg(...) 传入)。 */
    private void require(boolean cond, int code, String message) {
        if (!cond) {
            throw new BizException(code, message);
        }
    }

    private Map<String, Object> loadSchedule(long orgId, long scheduleId) {
        try {
            return jdbc.queryForMap(
                    "select id, order_id, status, work_status from construction_schedule "
                            + "where id = ? and org_id = ?", scheduleId, orgId);
        } catch (EmptyResultDataAccessException e) {
            throw new BizException(ConstructionService.CODE_NOT_FOUND, m.msg("construction.notfound"));
        }
    }

    private WorkerRow loadWorker(long orgId, long workerId) {
        List<WorkerRow> rows = jdbc.query("""
                select id, order_id, schedule_id, user_id, status from construction_worker
                where id = ? and org_id = ?
                """, (rs, rowNum) -> new WorkerRow(rs.getLong("id"), rs.getLong("order_id"),
                rs.getLong("schedule_id"), rs.getLong("user_id"), rs.getInt("status")), workerId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(ConstructionService.CODE_NOT_FOUND, m.msg("construction.notfound"));
        }
        return rows.get(0);
    }

    private int lastAction(long workerId) {
        List<Integer> rows = jdbc.queryForList(
                "select action from construction_worker_record where worker_id = ? order by id desc limit 1",
                Integer.class, workerId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private void insertRecord(long orgId, WorkerRow worker, int action, long operatorId) {
        jdbc.update("""
                insert into construction_worker_record(org_id, worker_id, order_id, schedule_id, action,
                    operator_id, operator_name)
                values (?,?,?,?,?,?,?)
                """, orgId, worker.id(), worker.orderId(), worker.scheduleId(), action, operatorId,
                displayName(operatorId));
    }

    private void requireUser(long userId) {
        Long count = jdbc.queryForObject(
                "select count(*) from yz_user where id = ?", Long.class, userId);
        if (count == null || count == 0) {
            throw new BizException(ConstructionService.CODE_NOT_FOUND, m.msg("construction.notfound"));
        }
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private boolean inStars(Integer score) {
        return score != null && score >= 1 && score <= 5;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * i18n 适配器(团队/档案服务与主服务共用消息口径);独立小类避免循环依赖主服务。
     */
    @org.springframework.stereotype.Component
    public static class MessageSourceAdapter {

        private final org.springframework.context.MessageSource messages;

        public MessageSourceAdapter(org.springframework.context.MessageSource messages) {
            this.messages = messages;
        }

        public String msg(String key) {
            return messages.getMessage(key, null,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }

        public String msgArgs(String key, Object... args) {
            return messages.getMessage(key, args,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }
    }
}
