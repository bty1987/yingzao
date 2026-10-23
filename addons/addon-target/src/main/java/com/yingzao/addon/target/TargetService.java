package com.yingzao.addon.target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.org.OrgScopeService;
import com.yingzao.core.web.BizException;

/**
 * 经营目标服务(docs/08 S3 首个消费方;docs/07 月目标+阶段模型):目标挂组织树上任一节点,
 * 达成率 = 节点子树实绩 ÷ 节点目标;阶段目标把"月"切成时间阶段分别立标(月初淡、月末冲)。
 *
 * <p>业绩线:contract 签约额(实绩经 DecorationApi 契约按组织聚合)/channel 渠道进线数
 * (MarketApi 契约)——禁直查他域表,目标节点子树(S1 OrgScopeService)内各组织求和;
 * 阶段实绩=阶段日期区间的日切片(DecorationApi/MarketApi Between 契约)。
 * 调整留痕(docs/07 提交记录模型):改额强制写 old→new+说明。
 *
 * <p>V1 诚实边界:业务单据目前只有公司级 org 归属,分区/部门节点实绩恒 0(分母就绪,
 * 分子待业务单据部门归属模型演进)。
 */
@Service
public class TargetService {

    public static final int CODE_PARAM = 370001;
    public static final int CODE_NOT_FOUND = 370002;
    public static final int CODE_EXISTS = 370003;
    public static final int CODE_AMOUNT = 370004;
    public static final int CODE_MONTH_FORMAT = 370005;
    public static final int CODE_NODE = 370006;
    public static final int CODE_STAGE_NOT_FOUND = 370007;
    public static final int CODE_STAGE_KEY_DUP = 370008;
    public static final int CODE_STAGE_RANGE = 370009;
    public static final int CODE_STAGE_OVERLAP = 370010;

    public static final String LINE_CONTRACT = "contract";
    public static final String LINE_CHANNEL = "channel";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final DecorationApi decorations;
    private final com.yingzao.addons.api.MarketApi markets;
    private final OrgScopeService orgScopes;

    public TargetService(JdbcTemplate jdbc, MessageSource messages, DecorationApi decorations,
            com.yingzao.addons.api.MarketApi markets, OrgScopeService orgScopes) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.decorations = decorations;
        this.markets = markets;
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

    public record CreateCmd(Long orgNode, String yearMonth, Long amount, String remark, String line) {
    }

    /** 建目标:节点须存在;同节点同月同线唯一;金额>0;月份 yyyy-MM。 */
    @Transactional
    public long create(long orgId, CreateCmd cmd, long operatorId) {
        require(cmd != null && cmd.orgNode() != null && cmd.orgNode() > 0, CODE_NODE, "target.node");
        require(cmd.yearMonth() != null && cmd.yearMonth().matches("\\d{4}-\\d{2}"),
                CODE_MONTH_FORMAT, "target.monthFormat");
        require(cmd.amount() != null && cmd.amount() > 0, CODE_AMOUNT, "target.amount");
        String line = cmd.line() == null || cmd.line().isBlank() ? LINE_CONTRACT : cmd.line().trim();
        require(line.equals(LINE_CONTRACT) || line.equals(LINE_CHANNEL), CODE_PARAM, "target.line");
        requireNodeExists(cmd.orgNode());
        try {
            jdbc.update("""
                    insert into target_plan(org_id, org_node, ym, line, amount, status, remark, created_by)
                    values (?,?,?,?,?,0,?,?)
                    """, orgId, cmd.orgNode(), cmd.yearMonth().trim(), line,
                    cmd.amount(), blankToNull(cmd.remark()), operatorId);
            return jdbc.queryForObject("select last_insert_id()", Long.class);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_EXISTS, msg("target.exists"));
        }
    }

    /** 调整金额:强制留痕(old→new+说明,docs/07 提交记录模型)。 */
    @Transactional
    public void adjust(long orgId, long id, Long newAmount, String remark, long operatorId) {
        Map<String, Object> plan = load(orgId, id);
        require(newAmount != null && newAmount > 0, CODE_AMOUNT, "target.amount");
        long oldAmount = ((Number) plan.get("amount")).longValue();
        if (newAmount == oldAmount) {
            return;
        }
        jdbc.update("update target_plan set amount = ? where id = ? and org_id = ?", newAmount, id, orgId);
        jdbc.update("""
                insert into target_submission(plan_id, old_amount, new_amount, remark, created_by)
                values (?,?,?,?,?)
                """, id, oldAmount, newAmount, blankToNull(remark), operatorId);
    }

    /** 停/启:停用后达成率分母不出现该目标。 */
    @Transactional
    public void changeStatus(long orgId, long id, int status) {
        require(status == 0 || status == 1, CODE_PARAM, "target.param");
        load(orgId, id);
        jdbc.update("update target_plan set status = ? where id = ? and org_id = ?", status, id, orgId);
    }

    /** 删除(错录场景;留痕与阶段一并删除)。 */
    @Transactional
    public void delete(long orgId, long id) {
        load(orgId, id);
        jdbc.update("delete from target_stage where plan_id = ?", id);
        jdbc.update("delete from target_submission where plan_id = ?", id);
        jdbc.update("delete from target_plan where id = ? and org_id = ?", id, orgId);
    }

    /** 列表(节点/月份过滤可选;每行实时聚合子树实绩与达成率)。 */
    public List<Map<String, Object>> list(long orgId, Long node, String yearMonth) {
        StringBuilder where = new StringBuilder(" where p.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (node != null && node > 0) {
            where.append(" and p.org_node = ?");
            args.add(node);
        }
        if (yearMonth != null && !yearMonth.isBlank()) {
            where.append(" and p.ym = ?");
            args.add(yearMonth.trim());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.id, p.org_node, o.name as node_name, p.ym, p.line,
                       p.amount, p.status, p.remark, p.created_at
                from target_plan p left join organization o on o.id = p.org_node
                """ + where + " order by p.ym desc, p.id desc", args.toArray());
        for (Map<String, Object> r : rows) {
            long rowNode = ((Number) r.get("org_node")).longValue();
            String ym = (String) r.get("ym");
            boolean enabled = ((Number) r.get("status")).intValue() == 0;
            long amount = ((Number) r.get("amount")).longValue();
            long actual = enabled ? actualOf(rowNode, (String) r.get("line"), ym) : 0;
            r.put("actual", actual);
            r.put("rate", amount == 0 ? null
                    : (long) Math.min(actual * 1000 / amount, 999999));
        }
        return rows;
    }

    /** 单节点达成率(看板数据源;按业绩线,缺省 contract;取该节点该月启用目标,无目标返回 target=null)。 */
    public Map<String, Object> achievement(long orgId, long node, String line, String yearMonth) {
        String effectiveLine = line == null || line.isBlank() ? LINE_CONTRACT : line.trim();
        List<Map<String, Object>> plans = jdbc.queryForList("""
                select id, amount from target_plan
                where org_id = ? and org_node = ? and ym = ? and line = ? and status = 0
                order by id limit 1
                """, orgId, node, yearMonth, effectiveLine);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("yearMonth", yearMonth);
        out.put("line", effectiveLine);
        long actual = actualOf(node, effectiveLine, yearMonth);
        out.put("actual", actual);
        if (plans.isEmpty()) {
            out.put("target", null);
            out.put("rate", null);
            return out;
        }
        long amount = ((Number) plans.get(0).get("amount")).longValue();
        out.put("target", amount);
        out.put("rate", amount == 0 ? null : (long) Math.min(actual * 1000 / amount, 999999));
        return out;
    }

    /** 调整留痕列表(旧值→新值+说明+操作时间)。 */
    public List<Map<String, Object>> records(long orgId, long id) {
        load(orgId, id);
        return jdbc.queryForList("""
                select old_amount, new_amount, remark, created_at
                from target_submission where plan_id = ? order by id
                """, id);
    }

    // ---- 阶段目标(V1.2,docs/07 阶段模型:月内切阶段分别立标) ----

    public record StageCmd(String stageKey, Integer sortNo, String beginDay, String endDay,
            Long amount, String remark) {
    }

    /** 建阶段:父目标须启用;起止日必须落在父目标 ym 月份内;同 plan stageKey 唯一;
     *  同 plan 区间重叠拒绝(fl-yy 语义:同线重叠=录入错误)。 */
    @Transactional
    public long createStage(long orgId, long planId, StageCmd cmd, long operatorId) {
        Map<String, Object> plan = load(orgId, planId);
        require(((Number) plan.get("status")).intValue() == 0, CODE_NOT_FOUND, "target.notFound");
        require(cmd != null && cmd.stageKey() != null && !cmd.stageKey().isBlank(),
                CODE_PARAM, "target.param");
        require(cmd.amount() != null && cmd.amount() > 0, CODE_AMOUNT, "target.amount");
        String ym = (String) plan.get("ym");
        java.time.LocalDate begin = parseDay(cmd.beginDay());
        java.time.LocalDate end = parseDay(cmd.endDay());
        require(begin != null && end != null && !end.isBefore(begin), CODE_STAGE_RANGE, "target.stage.range");
        require(ym.equals(begin.toString().substring(0, 7)) && ym.equals(end.toString().substring(0, 7)),
                CODE_STAGE_RANGE, "target.stage.range");
        Integer dup = jdbc.queryForObject(
                "select count(*) from target_stage where plan_id = ? and stage_key = ?",
                Integer.class, planId, cmd.stageKey().trim());
        require(dup == null || dup == 0, CODE_STAGE_KEY_DUP, "target.stage.keyDup");
        Integer overlap = jdbc.queryForObject("""
                select count(*) from target_stage
                where plan_id = ? and status = 0
                  and not (end_day < ? or begin_day > ?)
                """, Integer.class, planId, begin, end);
        require(overlap == null || overlap == 0, CODE_STAGE_OVERLAP, "target.stage.overlap");
        jdbc.update("""
                insert into target_stage(plan_id, org_id, stage_key, sort_no, begin_day, end_day,
                    amount, status, remark, created_by)
                values (?,?,?,?,?,?,?,0,?,?)
                """, planId, orgId, cmd.stageKey().trim(),
                cmd.sortNo() == null ? 0 : cmd.sortNo(), java.sql.Date.valueOf(begin),
                java.sql.Date.valueOf(end), cmd.amount(), blankToNull(cmd.remark()), operatorId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 调整阶段(金额/起止/顺序;stage_key 不可改——改=删重建)。 */
    @Transactional
    public void updateStage(long orgId, long stageId, StageCmd cmd, long operatorId) {
        Map<String, Object> stage = loadStage(orgId, stageId);
        long planId = ((Number) stage.get("plan_id")).longValue();
        Map<String, Object> plan = load(orgId, planId);
        String ym = (String) plan.get("ym");
        require(cmd != null && cmd.stageKey() != null && cmd.stageKey().trim().equals(stage.get("stage_key")),
                CODE_PARAM, "target.stage.keyFixed");
        require(cmd.amount() != null && cmd.amount() > 0, CODE_AMOUNT, "target.amount");
        java.time.LocalDate begin = parseDay(cmd.beginDay());
        java.time.LocalDate end = parseDay(cmd.endDay());
        require(begin != null && end != null && !end.isBefore(begin), CODE_STAGE_RANGE, "target.stage.range");
        require(ym.equals(begin.toString().substring(0, 7)) && ym.equals(end.toString().substring(0, 7)),
                CODE_STAGE_RANGE, "target.stage.range");
        Integer overlap = jdbc.queryForObject("""
                select count(*) from target_stage
                where plan_id = ? and status = 0 and id != ?
                  and not (end_day < ? or begin_day > ?)
                """, Integer.class, planId, stageId, begin, end);
        require(overlap == null || overlap == 0, CODE_STAGE_OVERLAP, "target.stage.overlap");
        jdbc.update("""
                update target_stage set sort_no = ?, begin_day = ?, end_day = ?, amount = ?, remark = ?
                where id = ? and org_id = ?
                """,
                cmd.sortNo() == null ? stage.get("sort_no") : cmd.sortNo(),
                java.sql.Date.valueOf(begin), java.sql.Date.valueOf(end), cmd.amount(),
                blankToNull(cmd.remark()), stageId, orgId);
    }

    /** 阶段启停。 */
    @Transactional
    public void changeStageStatus(long orgId, long stageId, int status) {
        require(status == 0 || status == 1, CODE_PARAM, "target.param");
        loadStage(orgId, stageId);
        jdbc.update("update target_stage set status = ? where id = ? and org_id = ?", status, stageId, orgId);
    }

    /** 阶段删除。 */
    @Transactional
    public void deleteStage(long orgId, long stageId) {
        loadStage(orgId, stageId);
        jdbc.update("delete from target_stage where id = ? and org_id = ?", stageId, orgId);
    }

    /** 阶段列表(含实时实绩/达成率;实绩=阶段区间内父目标口径的日切片聚合)。 */
    public List<Map<String, Object>> stages(long orgId, long planId) {
        load(orgId, planId);
        Map<String, Object> plan = load(orgId, planId);
        String line = (String) plan.get("line");
        long node = ((Number) plan.get("org_node")).longValue();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, plan_id, stage_key, sort_no, begin_day, end_day, amount, status, remark, created_at
                from target_stage where plan_id = ? order by sort_no, id
                """, planId);
        for (Map<String, Object> r : rows) {
            boolean enabled = ((Number) r.get("status")).intValue() == 0;
            long amount = ((Number) r.get("amount")).longValue();
            long actual = 0;
            if (enabled) {
                java.time.LocalDate b = ((java.sql.Date) r.get("begin_day")).toLocalDate();
                java.time.LocalDate e = ((java.sql.Date) r.get("end_day")).toLocalDate();
                actual = actualOfRange(node, line, b, e);
            }
            r.put("actual", actual);
            r.put("rate", amount == 0 ? null : (long) Math.min(actual * 1000 / amount, 999999));
        }
        return rows;
    }

    /** 子树实绩:目标节点 scope(自身+启用子树)内各组织按业绩线求和(签约额厘/渠道线进线个)。 */
    private long actualOf(long node, String line, String yearMonth) {
        List<Long> scope = orgScopes.scopeOrgIds(node);
        long total = 0;
        for (Long org : scope) {
            total += LINE_CHANNEL.equals(line)
                    ? markets.inboundTotalByMonth(org, yearMonth)
                    : decorations.contractSignedTotalByMonth(org, yearMonth);
        }
        return total;
    }

    /** 区间版实绩(阶段用):签约线按首签日落于区间;渠道线按登记日落于区间。 */
    private long actualOfRange(long node, String line, java.time.LocalDate begin, java.time.LocalDate end) {
        List<Long> scope = orgScopes.scopeOrgIds(node);
        long total = 0;
        for (Long org : scope) {
            total += LINE_CHANNEL.equals(line)
                    ? markets.inboundTotalBetween(org, begin.toString(), end.toString())
                    : decorations.contractSignedTotalBetween(org, begin.toString(), end.toString());
        }
        return total;
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_node, ym, line, amount, status from target_plan"
                        + " where id = ? and org_id = ?", id, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "target.notFound");
        return rows.get(0);
    }

    private Map<String, Object> loadStage(long orgId, long stageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, plan_id, stage_key, sort_no, begin_day, end_day, amount, status"
                        + " from target_stage where id = ? and org_id = ?", stageId, orgId);
        require(!rows.isEmpty(), CODE_STAGE_NOT_FOUND, "target.stage.notFound");
        return rows.get(0);
    }

    /** yyyy-MM-dd;非法返回 null(校验层报错)。 */
    private java.time.LocalDate parseDay(String text) {
        if (text == null || !text.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void requireNodeExists(long nodeId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from organization where id = ?", Integer.class, nodeId);
        require(n != null && n > 0, CODE_NODE, "target.node");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
