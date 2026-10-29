package com.yingzao.addon.target;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.org.OrgScopeService;
import com.yingzao.core.web.BizException;

/**
 * PK 对赌子域服务(docs/prd/addon-target-pk.md D3,按推荐拍板第 102 次实现):
 * 管理者按周期为参赛单元(组织树节点)发起对赌——筹备配模板(四档直配+PK 金),对赌期逐单元锁档
 * (选档定目标、奖金=PK金×档位码),确认开赛后自动聚合实绩(与 target_plan 同契约同口径),
 * 周期结束由 yz_plan 定时任务冻结快照并归档;判胜负纯人工,奖金只记录不发放。
 *
 * <p>fl-yy 13 表收敛 3 表(event/unit/snapshot);老缺陷收敛:状态机守卫替代物理删、
 * 草稿挂 event 行内(org 守卫+仅筹备态)、参赛单元=节点精确 id(替代 LIKE 模糊)、
 * performance_source 单源+快照冻结、奖金池/承诺额查询聚合不回写 event、
 * 金标准矩阵/跨赛分组/推送/打点不迁(复用=从上一届复制)。
 *
 * <p>诚实边界(继承 target V1):业务单据公司级归属,分区/部门节点自动实绩恒 0;
 * 手工实绩双轨全员可用(source 留痕),部门级自动聚合随单据归属模型自然点亮。
 */
@Service
public class PkService {

    public static final int CODE_EVENT_NOT_FOUND = 370101;
    public static final int CODE_STATUS = 370102;
    public static final int CODE_EXISTS = 370103;
    public static final int CODE_UNIT = 370104;
    public static final int CODE_TEMPLATE = 370105;
    public static final int CODE_NO_LOCKED = 370106;
    public static final int CODE_NOT_LOCKED = 370107;
    public static final int CODE_LOCK_PHASE = 370108;
    public static final int CODE_ARCHIVED = 370109;
    public static final int CODE_MANUAL_OFF = 370110;
    public static final int CODE_VOID_REASON = 370111;
    public static final int CODE_UNIT_LOCKED = 370113;
    public static final int CODE_PERIOD_DATE = 370114;
    public static final int CODE_DRAFT_EMPTY = 370115;

    /** 参数类错误复用 target 主域码(同模块同码段,语义=参数不合法)。 */
    public static final int CODE_PARAM = TargetService.CODE_PARAM;

    public static final int STATUS_PREP = 0;
    public static final int STATUS_BET = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_ARCHIVED = 3;
    public static final int STATUS_VOID = 4;

    public static final int CYCLE_MONTH = 1;
    public static final int CYCLE_QUARTER = 2;

    public static final int SOURCE_AUTO = 1;
    public static final int SOURCE_MANUAL = 2;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final ObjectMapper objectMapper;
    private final DecorationApi decorations;
    private final OrgScopeService orgScopes;

    public PkService(JdbcTemplate jdbc, MessageSource messages, ObjectMapper objectMapper,
            DecorationApi decorations, OrgScopeService orgScopes) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.decorations = decorations;
        this.orgScopes = orgScopes;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }

    // ---- 模板单元(入参/校验/落行,publish 与单行 upsert 共用) ----

    public record UnitCmd(Long orgNode, List<String> staffs, Long guarantee, Long base,
            Long breakthrough, Long challenge, Long pkMoney, Long bonusPreset,
            Integer manualEnabled) {
    }

    /** 校验单元模板:节点须存在且启用;四档目标与 PK 金 > 0;奖金预设可空但非空须 > 0。 */
    private void validateUnit(UnitCmd u, Set<Long> seenNodes) {
        require(u != null && u.orgNode() != null && u.orgNode() > 0, CODE_UNIT, "target.pk.unit");
        require(seenNodes.add(u.orgNode()), CODE_UNIT, "target.pk.unit");
        require(u.guarantee() != null && u.guarantee() > 0 && u.base() != null && u.base() > 0
                && u.breakthrough() != null && u.breakthrough() > 0
                && u.challenge() != null && u.challenge() > 0
                && u.pkMoney() != null && u.pkMoney() > 0,
                CODE_TEMPLATE, "target.pk.template");
        require(u.bonusPreset() == null || u.bonusPreset() > 0, CODE_TEMPLATE, "target.pk.template");
        require(u.manualEnabled() == null || (u.manualEnabled() == 0 || u.manualEnabled() == 1),
                CODE_PARAM, "target.param");
        Map<String, Object> node = jdbc.queryForList(
                "select id, status from organization where id = ?", u.orgNode())
                .stream().findFirst().orElse(null);
        require(node != null, CODE_UNIT, "target.pk.unit");
        require(((Number) node.get("status")).intValue() == 0, CODE_UNIT, "target.pk.unit");
    }

    private String staffsJson(List<String> staffs) {
        if (staffs == null || staffs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(staffs);
        } catch (Exception e) {
            throw new BizException(CODE_PARAM, msg("target.param"));
        }
    }

    private void upsertUnit(long orgId, long eventId, UnitCmd u) {
        jdbc.update("""
                insert into target_pk_unit(event_id, org_id, org_node, staffs, guarantee, base,
                    breakthrough, challenge, pk_money, bonus_preset, manual_enabled)
                values (?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update staffs = values(staffs), guarantee = values(guarantee),
                    base = values(base), breakthrough = values(breakthrough),
                    challenge = values(challenge), pk_money = values(pk_money),
                    bonus_preset = values(bonus_preset), manual_enabled = values(manual_enabled)
                """, eventId, orgId, u.orgNode(), staffsJson(u.staffs()),
                u.guarantee(), u.base(), u.breakthrough(), u.challenge(), u.pkMoney(),
                u.bonusPreset(), u.manualEnabled() == null ? 0 : u.manualEnabled());
    }

    // ---- 赛事 CRUD ----

    public record EventCmd(Integer cycleType, String period, String name, String startDate,
            String endDate, Integer baseTier, Integer tierIncrement) {
    }

    /** 建赛事:周期格式按类型校验(月 yyyy-MM/季 yyyy-Qn),起止日须落在周期内;同期同型唯一。 */
    @Transactional
    public long createEvent(long orgId, EventCmd cmd, long operatorId) {
        require(cmd != null && cmd.cycleType() != null
                && (cmd.cycleType() == CYCLE_MONTH || cmd.cycleType() == CYCLE_QUARTER),
                CODE_PARAM, "target.param");
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_PARAM, "target.param");
        require(cmd.baseTier() == null || (cmd.baseTier() >= 2 && cmd.baseTier() <= 5),
                CODE_PARAM, "target.param");
        require(cmd.tierIncrement() == null || cmd.tierIncrement() > 0, CODE_PARAM, "target.param");
        LocalDate[] range = resolvePeriod(cmd.cycleType(), cmd.period());
        LocalDate begin = parseDay(cmd.startDate());
        LocalDate end = parseDay(cmd.endDate());
        require(begin != null && end != null && !end.isBefore(begin), CODE_PARAM, "target.param");
        require(!begin.isBefore(range[0]) && !begin.isAfter(range[1])
                && !end.isBefore(range[0]) && !end.isAfter(range[1]),
                CODE_PERIOD_DATE, "target.pk.periodDate");
        try {
            jdbc.update("""
                    insert into target_pk_event(org_id, cycle_type, period, name, start_date, end_date,
                        status, base_tier, tier_increment, created_by)
                    values (?,?,?,?,?,?,0,?,?,?)
                    """, orgId, cmd.cycleType(), cmd.period().trim(), cmd.name().trim(),
                    java.sql.Date.valueOf(begin), java.sql.Date.valueOf(end),
                    cmd.baseTier(), cmd.tierIncrement(), operatorId);
            return jdbc.queryForObject("select last_insert_id()", Long.class);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_EXISTS, msg("target.pk.exists"));
        }
    }

    /** 从上一届复制赛事(含单元模板;锁档/实绩字段清零,新赛事回筹备态)——开放问题 2 推荐兑现。 */
    @Transactional
    public long copyEvent(long orgId, long sourceId, EventCmd cmd, long operatorId) {
        Map<String, Object> source = loadEvent(orgId, sourceId);
        require(cmd != null && cmd.cycleType() != null
                && (cmd.cycleType() == CYCLE_MONTH || cmd.cycleType() == CYCLE_QUARTER),
                CODE_PARAM, "target.param");
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_PARAM, "target.param");
        LocalDate[] range = resolvePeriod(cmd.cycleType(), cmd.period());
        LocalDate begin = parseDay(cmd.startDate());
        LocalDate end = parseDay(cmd.endDate());
        require(begin != null && end != null && !end.isBefore(begin), CODE_PARAM, "target.param");
        require(!begin.isBefore(range[0]) && !begin.isAfter(range[1])
                && !end.isBefore(range[0]) && !end.isAfter(range[1]),
                CODE_PERIOD_DATE, "target.pk.periodDate");
        Integer baseTier = cmd.baseTier() != null ? cmd.baseTier()
                : (Integer) source.get("base_tier");
        Integer tierIncrement = cmd.tierIncrement() != null ? cmd.tierIncrement()
                : (Integer) source.get("tier_increment");
        long newId;
        try {
            jdbc.update("""
                    insert into target_pk_event(org_id, cycle_type, period, name, start_date, end_date,
                        status, base_tier, tier_increment, created_by)
                    values (?,?,?,?,?,?,0,?,?,?)
                    """, orgId, cmd.cycleType(), cmd.period().trim(), cmd.name().trim(),
                    java.sql.Date.valueOf(begin), java.sql.Date.valueOf(end),
                    baseTier, tierIncrement, operatorId);
            newId = jdbc.queryForObject("select last_insert_id()", Long.class);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_EXISTS, msg("target.pk.exists"));
        }
        jdbc.update("""
                insert into target_pk_unit(event_id, org_id, org_node, staffs, guarantee, base,
                    breakthrough, challenge, pk_money, bonus_preset, manual_enabled)
                select ?, org_id, org_node, staffs, guarantee, base, breakthrough, challenge,
                    pk_money, bonus_preset, manual_enabled
                from target_pk_unit where event_id = ?
                """, newId, sourceId);
        return newId;
    }

    /** 赛事列表(状态/周期过滤;承诺额与奖金池=查询时对已锁单元聚合,不落列不回写)。 */
    public List<Map<String, Object>> listEvents(long orgId, Integer status, Integer cycleType,
            String period) {
        StringBuilder where = new StringBuilder(" where e.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and e.status = ?");
            args.add(status);
        }
        if (cycleType != null) {
            where.append(" and e.cycle_type = ?");
            args.add(cycleType);
        }
        if (period != null && !period.isBlank()) {
            where.append(" and e.period = ?");
            args.add(period.trim());
        }
        return jdbc.queryForList("""
                select e.id, e.cycle_type, e.period, e.name, e.start_date, e.end_date, e.status,
                       e.base_tier, e.void_reason,
                       (select count(*) from target_pk_unit u where u.event_id = e.id) as unit_count,
                       (select count(*) from target_pk_unit u where u.event_id = e.id
                           and u.target_amount is not null) as locked_count,
                       (select coalesce(sum(u.target_amount), 0) from target_pk_unit u
                           where u.event_id = e.id and u.target_amount is not null) as commitment,
                       (select coalesce(sum(u.bonus_amount), 0) from target_pk_unit u
                           where u.event_id = e.id and u.target_amount is not null) as bonus_pool
                from target_pk_event e
                """ + where + " order by e.period desc, e.id desc", args.toArray());
    }

    /** 赛事全景:单元实时实绩(进行中)或快照终值(已归档);聚合统计查询时计算。 */
    public Map<String, Object> detail(long orgId, long eventId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        List<Map<String, Object>> units = jdbc.queryForList("""
                select u.id, u.org_node, o.name as node_name, u.staffs, u.guarantee, u.base,
                       u.breakthrough, u.challenge, u.pk_money, u.bonus_preset, u.manual_enabled,
                       u.manual_performance, u.manual_remark, u.selected_tier, u.target_amount,
                       u.bonus_amount, u.custom_target, u.custom_bonus, u.lock_by, u.lock_at
                from target_pk_unit u left join organization o on o.id = u.org_node
                where u.event_id = ? order by u.id
                """, eventId);
        boolean archived = status == STATUS_ARCHIVED;
        Map<Long, Map<String, Object>> snapshots = new LinkedHashMap<>();
        if (archived) {
            for (Map<String, Object> s : jdbc.queryForList(
                    "select org_node, target_amount, final_amount, performance_source, final_data, snapshot_hash"
                            + " from target_pk_snapshot where event_id = ?", eventId)) {
                snapshots.put(((Number) s.get("org_node")).longValue(), s);
            }
        }
        long commitment = 0;
        long bonusPool = 0;
        int lockedCount = 0;
        for (Map<String, Object> u : units) {
            boolean locked = u.get("target_amount") != null;
            if (locked) {
                lockedCount++;
                commitment += ((Number) u.get("target_amount")).longValue();
                bonusPool += ((Number) u.get("bonus_amount")).longValue();
            }
            Long finalAmount = null;
            Integer source = null;
            if (archived) {
                Map<String, Object> s = snapshots.get(((Number) u.get("org_node")).longValue());
                if (s != null) {
                    finalAmount = ((Number) s.get("final_amount")).longValue();
                    source = ((Number) s.get("performance_source")).intValue();
                }
            } else if (status == STATUS_RUNNING) {
                boolean manual = ((Number) u.get("manual_enabled")).intValue() == 1
                        && u.get("manual_performance") != null;
                finalAmount = manual
                        ? ((Number) u.get("manual_performance")).longValue()
                        : autoActual(event, ((Number) u.get("org_node")).longValue());
                source = manual ? SOURCE_MANUAL : SOURCE_AUTO;
            }
            u.put("final_amount", finalAmount);
            u.put("performance_source", source);
            Long target = locked ? ((Number) u.get("target_amount")).longValue() : null;
            u.put("rate", finalAmount == null || target == null || target == 0 ? null
                    : (long) Math.min(finalAmount * 1000 / target, 999999));
        }
        Map<String, Object> out = new LinkedHashMap<>(event);
        out.put("units", units);
        out.put("lockedCount", lockedCount);
        out.put("unitCount", units.size());
        out.put("commitment", commitment);
        out.put("bonusPool", bonusPool);
        out.put("hasDraft", event.get("draft_data") != null);
        return out;
    }

    // ---- 草稿与发布(修老草稿无身份/状态校验:挂 event 行内+org 守卫+仅筹备态) ----

    public record DraftCmd(List<UnitCmd> units) {
    }

    /** 保存整包模板草稿(仅筹备态;任意覆盖=显式动作,org 守卫已兜底)。 */
    @Transactional
    public void saveDraft(long orgId, long eventId, DraftCmd cmd) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        require(((Number) event.get("status")).intValue() == STATUS_PREP, CODE_STATUS, "target.pk.status");
        String json = null;
        if (cmd != null && cmd.units() != null && !cmd.units().isEmpty()) {
            Set<Long> seen = new HashSet<>();
            for (UnitCmd u : cmd.units()) {
                require(u != null && u.orgNode() != null && u.orgNode() > 0, CODE_UNIT, "target.pk.unit");
                require(seen.add(u.orgNode()), CODE_UNIT, "target.pk.unit");
            }
            try {
                json = objectMapper.writeValueAsString(cmd.units());
            } catch (Exception e) {
                throw new BizException(CODE_PARAM, msg("target.param"));
            }
        }
        jdbc.update("update target_pk_event set draft_data = ? where id = ? and org_id = ?",
                json, eventId, orgId);
    }

    /** 发布草稿:逐单元校验落行(upsert),发布后清草稿。 */
    @Transactional
    public int publishDraft(long orgId, long eventId, long operatorId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status == STATUS_PREP || status == STATUS_BET, CODE_STATUS, "target.pk.status");
        String draft = (String) event.get("draft_data");
        require(draft != null && !draft.isBlank(), CODE_DRAFT_EMPTY, "target.pk.draftEmpty");
        List<UnitCmd> units;
        try {
            units = List.of(objectMapper.readValue(draft, UnitCmd[].class));
        } catch (Exception e) {
            throw new BizException(CODE_PARAM, msg("target.param"));
        }
        require(!units.isEmpty(), CODE_DRAFT_EMPTY, "target.pk.draftEmpty");
        Set<Long> seen = new HashSet<>();
        for (UnitCmd u : units) {
            require(u != null && u.orgNode() != null && u.orgNode() > 0, CODE_UNIT, "target.pk.unit");
            require(seen.add(u.orgNode()), CODE_UNIT, "target.pk.unit");
            Map<String, Object> existing = jdbc.queryForList(
                    "select target_amount from target_pk_unit where event_id = ? and org_node = ?",
                    eventId, u.orgNode()).stream().findFirst().orElse(null);
            require(existing == null || existing.get("target_amount") == null,
                    CODE_UNIT_LOCKED, "target.pk.unitLocked");
            validateUnit(u, new HashSet<>());
            upsertUnit(orgId, eventId, u);
        }
        jdbc.update("update target_pk_event set draft_data = null where id = ? and org_id = ?",
                eventId, orgId);
        return units.size();
    }

    // ---- 单元模板动作 ----

    /** 单行模板 upsert(筹备/对赌中;已锁档拒)。 */
    @Transactional
    public void upsertUnitRow(long orgId, long eventId, UnitCmd cmd) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status == STATUS_PREP || status == STATUS_BET, CODE_STATUS, "target.pk.status");
        require(cmd != null && cmd.orgNode() != null && cmd.orgNode() > 0, CODE_UNIT, "target.pk.unit");
        Map<String, Object> existing = jdbc.queryForList(
                "select target_amount from target_pk_unit where event_id = ? and org_node = ?",
                eventId, cmd.orgNode()).stream().findFirst().orElse(null);
        require(existing == null || existing.get("target_amount") == null,
                CODE_UNIT_LOCKED, "target.pk.unitLocked");
        validateUnit(cmd, new HashSet<>());
        upsertUnit(orgId, eventId, cmd);
    }

    /** 模板行删除:仅筹备态(对赌中单元已进入对赌语义,删行破坏承诺;修老无守卫物理删)。 */
    @Transactional
    public void deleteUnit(long orgId, long eventId, long orgNode) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        require(((Number) event.get("status")).intValue() == STATUS_PREP, CODE_STATUS, "target.pk.status");
        jdbc.update("delete from target_pk_unit where event_id = ? and org_node = ? and org_id = ?",
                eventId, orgNode, orgId);
    }

    // ---- 状态机动作 ----

    public record LockCmd(Integer tier, Long customTarget, Long customBonus) {
    }

    /** 锁档:选档(目标=档位直配值,奖金=PK金×档位码)或全自定义;仅筹备/对赌中;首次锁档翻对赌中。 */
    @Transactional
    public void lock(long orgId, long eventId, long orgNode, LockCmd cmd, long operatorId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status == STATUS_PREP || status == STATUS_BET, CODE_LOCK_PHASE, "target.pk.lockPhase");
        Map<String, Object> unit = loadUnit(eventId, orgNode);
        require(cmd != null, CODE_PARAM, "target.param");
        Long target;
        Long bonus;
        Long customTarget = null;
        Long customBonus = null;
        Integer tier = cmd.tier();
        if (tier != null) {
            require(tier >= 2 && tier <= 5, CODE_PARAM, "target.param");
            target = tierTarget(unit, tier);
            bonus = ((Number) unit.get("pk_money")).longValue() * tier;
        } else {
            require(cmd.customTarget() != null && cmd.customTarget() > 0
                    && cmd.customBonus() != null && cmd.customBonus() > 0,
                    CODE_TEMPLATE, "target.pk.template");
            target = cmd.customTarget();
            bonus = cmd.customBonus();
            customTarget = cmd.customTarget();
            customBonus = cmd.customBonus();
        }
        jdbc.update("""
                update target_pk_unit set selected_tier = ?, target_amount = ?, bonus_amount = ?,
                    custom_target = ?, custom_bonus = ?, lock_by = ?, lock_at = now()
                where event_id = ? and org_node = ?
                """, tier, target, bonus, customTarget, customBonus, operatorId, eventId, orgNode);
        if (status == STATUS_PREP) {
            jdbc.update("update target_pk_event set status = 1 where id = ? and status = 0", eventId);
        }
    }

    /** 解锁:仅对赌中(筹备态无锁可解/进行中已开赛);未锁档拒。 */
    @Transactional
    public void unlock(long orgId, long eventId, long orgNode) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        require(((Number) event.get("status")).intValue() == STATUS_BET, CODE_STATUS, "target.pk.status");
        Map<String, Object> unit = loadUnit(eventId, orgNode);
        require(unit.get("target_amount") != null, CODE_NOT_LOCKED, "target.pk.notLocked");
        jdbc.update("""
                update target_pk_unit set selected_tier = null, target_amount = null,
                    bonus_amount = null, custom_target = null, custom_bonus = null,
                    lock_by = null, lock_at = null
                where event_id = ? and org_node = ?
                """, eventId, orgNode);
    }

    /** 重置:清全部锁档回筹备,模板保留(配置是劳动成果,修老 reset 物理删全部)。 */
    @Transactional
    public void reset(long orgId, long eventId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status == STATUS_PREP || status == STATUS_BET, CODE_STATUS, "target.pk.status");
        jdbc.update("""
                update target_pk_unit set selected_tier = null, target_amount = null,
                    bonus_amount = null, custom_target = null, custom_bonus = null,
                    lock_by = null, lock_at = null
                where event_id = ?
                """, eventId);
        jdbc.update("update target_pk_event set status = 0 where id = ? and org_id = ?", eventId, orgId);
    }

    /** 确认开赛:对赌中→进行中;守卫≥1 单元已锁档(筹备态先回对赌中再确认)。 */
    @Transactional
    public void confirm(long orgId, long eventId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        require(((Number) event.get("status")).intValue() == STATUS_BET, CODE_STATUS, "target.pk.status");
        Integer locked = jdbc.queryForObject(
                "select count(*) from target_pk_unit where event_id = ? and target_amount is not null",
                Integer.class, eventId);
        require(locked != null && locked > 0, CODE_NO_LOCKED, "target.pk.noLocked");
        jdbc.update("update target_pk_event set status = 2 where id = ? and status = 1", eventId);
    }

    /** 作废:仅筹备/对赌中(进行中/已归档不可);原因必填;终态留痕,零物理删。 */
    @Transactional
    public void voidEvent(long orgId, long eventId, String reason) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status == STATUS_PREP || status == STATUS_BET, CODE_STATUS, "target.pk.status");
        require(reason != null && !reason.isBlank(), CODE_VOID_REASON, "target.pk.voidReason");
        jdbc.update("update target_pk_event set status = 4, void_reason = ? where id = ? and org_id = ?",
                reason.trim(), eventId, orgId);
    }

    /** 手工实绩:进行中可录(已归档读快照拒);单元须启用手工实绩。 */
    @Transactional
    public void saveManual(long orgId, long eventId, long orgNode, Long performance, String remark) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        int status = ((Number) event.get("status")).intValue();
        require(status != STATUS_ARCHIVED, CODE_ARCHIVED, "target.pk.archived");
        require(status == STATUS_RUNNING, CODE_STATUS, "target.pk.status");
        Map<String, Object> unit = loadUnit(eventId, orgNode);
        require(((Number) unit.get("manual_enabled")).intValue() == 1, CODE_MANUAL_OFF, "target.pk.manualOff");
        require(performance != null && performance >= 0, CODE_PARAM, "target.param");
        jdbc.update("update target_pk_unit set manual_performance = ?, manual_remark = ?"
                + " where event_id = ? and org_node = ?", performance,
                remark == null || remark.isBlank() ? null : remark.trim(), eventId, orgNode);
    }

    // ---- 归档(yz_plan target-pk-archive 调用;快照时点=归档时点,零宽限歧义) ----

    /** 归档到期赛事(status=2 且 end_date<今天):逐单元生成快照→赛事置已归档;幂等可重放。 */
    @Transactional
    public int archiveDue(long orgId) {
        List<Long> due = jdbc.queryForList("""
                select id from target_pk_event
                where org_id = ? and status = ? and end_date < ?
                """, Long.class, orgId, STATUS_RUNNING, java.sql.Date.valueOf(LocalDate.now()));
        int count = 0;
        for (Long eventId : due) {
            count += archiveEvent(orgId, eventId);
        }
        return count;
    }

    private int archiveEvent(long orgId, long eventId) {
        Map<String, Object> event = loadEvent(orgId, eventId);
        List<Map<String, Object>> units = jdbc.queryForList("""
                select u.org_node, o.name as node_name, u.staffs, u.selected_tier, u.target_amount,
                       u.bonus_amount, u.manual_enabled, u.manual_performance
                from target_pk_unit u left join organization o on o.id = u.org_node
                where u.event_id = ? order by u.id
                """, eventId);
        for (Map<String, Object> u : units) {
            boolean manual = ((Number) u.get("manual_enabled")).intValue() == 1
                    && u.get("manual_performance") != null;
            long finalAmount = manual
                    ? ((Number) u.get("manual_performance")).longValue()
                    : autoActual(event, ((Number) u.get("org_node")).longValue());
            int source = manual ? SOURCE_MANUAL : SOURCE_AUTO;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orgNode", ((Number) u.get("org_node")).longValue());
            data.put("nodeName", u.get("node_name"));
            data.put("staffs", u.get("staffs"));
            data.put("selectedTier", u.get("selected_tier"));
            data.put("targetAmount", u.get("target_amount"));
            data.put("bonusAmount", u.get("bonus_amount"));
            data.put("finalAmount", finalAmount);
            data.put("performanceSource", source);
            Long target = u.get("target_amount") == null ? null
                    : ((Number) u.get("target_amount")).longValue();
            data.put("rate", target == null || target == 0 ? null
                    : (long) Math.min(finalAmount * 1000 / target, 999999));
            String json;
            try {
                json = objectMapper.writeValueAsString(data);
            } catch (Exception e) {
                throw new IllegalStateException("snapshot json", e);
            }
            jdbc.update("""
                    insert into target_pk_snapshot(event_id, org_node, target_amount, final_amount,
                        performance_source, final_data, snapshot_hash)
                    values (?,?,?,?,?,?,?)
                    on duplicate key update final_amount = values(final_amount),
                        performance_source = values(performance_source),
                        final_data = values(final_data), snapshot_hash = values(snapshot_hash)
                    """, eventId, ((Number) u.get("org_node")).longValue(),
                    target == null ? 0 : target, finalAmount, source, json, sha256(json));
        }
        jdbc.update("update target_pk_event set status = 3 where id = ? and status = 2", eventId);
        return units.size();
    }

    /** 归档快照列表(未归档返回空)。 */
    public List<Map<String, Object>> snapshots(long orgId, long eventId) {
        loadEvent(orgId, eventId);
        return jdbc.queryForList("""
                select s.id, s.org_node, o.name as node_name, s.target_amount, s.final_amount,
                       s.performance_source, s.final_data, s.snapshot_hash, s.created_at
                from target_pk_snapshot s left join organization o on o.id = s.org_node
                where s.event_id = ? order by s.final_amount desc
                """, eventId);
    }

    // ---- 内部 ----

    private Map<String, Object> loadEvent(long orgId, long eventId) {
        return jdbc.queryForList("select * from target_pk_event where id = ? and org_id = ?",
                        eventId, orgId).stream().findFirst()
                .orElseThrow(() -> new BizException(CODE_EVENT_NOT_FOUND, msg("target.pk.eventNotFound")));
    }

    private Map<String, Object> loadUnit(long eventId, long orgNode) {
        return jdbc.queryForList(
                        "select * from target_pk_unit where event_id = ? and org_node = ?", eventId, orgNode)
                .stream().findFirst()
                .orElseThrow(() -> new BizException(CODE_UNIT, msg("target.pk.unit")));
    }

    /** 档位直配目标:2 保底/3 冲刺/4 突破/5 挑战(fl-yy calculateTierTarget 直配语义,legacy 公式不迁)。 */
    private long tierTarget(Map<String, Object> unit, int tier) {
        String col = switch (tier) {
            case 2 -> "guarantee";
            case 3 -> "base";
            case 4 -> "breakthrough";
            case 5 -> "challenge";
            default -> throw new BizException(CODE_PARAM, msg("target.param"));
        };
        return ((Number) unit.get(col)).longValue();
    }

    /** 单元自动实绩=子树聚合(月赛 ByMonth/季赛 Between,与 target_plan 达成率同契约同口径)。 */
    private long autoActual(Map<String, Object> event, long orgNode) {
        int cycleType = ((Number) event.get("cycle_type")).intValue();
        LocalDate begin;
        LocalDate end;
        String ym = null;
        if (cycleType == CYCLE_MONTH) {
            ym = (String) event.get("period");
            begin = ((java.sql.Date) event.get("start_date")).toLocalDate();
            end = ((java.sql.Date) event.get("end_date")).toLocalDate();
        } else {
            begin = ((java.sql.Date) event.get("start_date")).toLocalDate();
            end = ((java.sql.Date) event.get("end_date")).toLocalDate();
        }
        long total = 0;
        for (Long org : orgScopes.scopeOrgIds(orgNode)) {
            total += cycleType == CYCLE_MONTH
                    ? decorations.contractSignedTotalByMonth(org, ym)
                    : decorations.contractSignedTotalBetween(org, begin.toString(), end.toString());
        }
        return total;
    }

    /** 周期解析:月 yyyy-MM → 当月首尾日;季 yyyy-Qn → 季度首尾日;非法即参数错误。 */
    private LocalDate[] resolvePeriod(int cycleType, String period) {
        require(period != null, CODE_PARAM, "target.param");
        String p = period.trim();
        try {
            if (cycleType == CYCLE_MONTH) {
                require(p.matches("\\d{4}-\\d{2}"), CODE_PARAM, "target.param");
                YearMonth ym = YearMonth.parse(p);
                return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            require(p.matches("\\d{4}-Q[1-4]"), CODE_PARAM, "target.param");
            String[] parts = p.split("-Q");
            int quarter = Integer.parseInt(parts[1]);
            YearMonth start = YearMonth.of(Integer.parseInt(parts[0]), quarter * 3 - 2);
            return new LocalDate[]{start.atDay(1), start.plusMonths(2).atEndOfMonth()};
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CODE_PARAM, msg("target.param"));
        }
    }

    private LocalDate parseDay(String text) {
        if (text == null || !text.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256", e);
        }
    }
}
