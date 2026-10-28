package com.yingzao.addon.balance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.org.OrgScopeService;
import com.yingzao.core.web.BizException;

/**
 * 毛利与管理线子域(docs/prd/addon-balance-margin.md,2026-10-28 按推荐拍板)。
 * 两类线:集团管理线(主体=组织树根)/分公司要求线(主体=根的直接子组织,直接配置不反算);
 * 每业务年度固定 17 周期(年 1+季 4+月 12),发布必须整年完整提交;
 * 发布 = 插版次 + append-only 全量 17 行快照 + 与上一版本逐周期逐口径 diff(仅 before≠after 记行);
 * 当前生效 = 主体该年最新版本快照(latest=id 最大),历史版本只读、无反发布;
 * 并发闸 = baseVersionId 比对最新版本,不一致拒 320107;
 * 权限 = S2 基座映射:集团守卫 = 操作者 scope 含根(替代 fl-yy orgId==222 硬编码);
 * 分公司守卫 = 目标 ∈ scope 且非根,且必须是根的直接子组织;实际值列 V1 恒 null(F1 后填充)。
 */
@Service
public class MarginLineService {

    public static final int LINE_GROUP = 1;
    public static final int LINE_BRANCH = 2;
    public static final int CYCLE_YEAR = 1;
    public static final int CYCLE_QUARTER = 2;
    public static final int CYCLE_MONTH = 3;
    public static final int RATE_COMPREHENSIVE = 1;
    public static final int RATE_PROJECT = 2;
    public static final int RATE_MAIN = 3;

    // 错误码 320100-320150 小段(addon.yml error-code-segment 320000 之下,沿 231200 子段先例)
    public static final int CODE_PARAM_EMPTY = 320101;
    public static final int CODE_YEAR_ILLEGAL = 320102;
    public static final int CODE_PERIODS_INCOMPLETE = 320103;
    public static final int CODE_PERIOD_ILLEGAL = 320104;
    public static final int CODE_PERIOD_DUP = 320105;
    public static final int CODE_RATE_RANGE = 320106;
    public static final int CODE_VERSION_STALE = 320107;
    public static final int CODE_GROUP_NOT_PUBLISHED = 320108;
    public static final int CODE_GROUP_SNAPSHOT_INCOMPLETE = 320109;
    public static final int CODE_BRANCH_TARGET_REQUIRED = 320110;
    public static final int CODE_BRANCH_FORBIDDEN = 320111;
    public static final int CODE_GROUP_FORBIDDEN = 320112;
    public static final int CODE_VERSION_NOT_FOUND = 320113;
    public static final int CODE_VERSION_TYPE_ILLEGAL = 320114;
    public static final int CODE_SNAPSHOT_INCOMPLETE = 320115;

    /** 发布提交项(服务端全量校验,不信任前端聚合) */
    public record MarginPeriod(int cycleType, String periodValue, int comprehensiveLine,
            int projectLine, int mainMaterialLine) {
    }

    public record GroupPublishRequest(Integer bizYear, Long baseVersionId, List<MarginPeriod> periods) {
    }

    public record BranchPublishRequest(Long orgId, Integer bizYear, Long baseVersionId,
            List<MarginPeriod> periods) {
    }

    private record Cycle(int cycleType, String periodValue) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final OrgScopeService orgScopes;

    public MarginLineService(JdbcTemplate jdbc, MessageSource messages, OrgScopeService orgScopes) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.orgScopes = orgScopes;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key, Object... args) {
        if (!cond) {
            throw new BizException(code, msg(key, args));
        }
    }

    /** 组织树根 id(单树一根,V108 种子 org 1=总部;防御性 limit 1) */
    private long rootOrgId() {
        Long root = jdbc.queryForObject(
                "select id from organization where parent_id is null order by id limit 1", Long.class);
        require(root != null, CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
        return root;
    }

    /** 操作者 scope 是否含根(S2 基座:登录组织即根才含根) */
    private boolean isGroupIdentity(long viewerOrg) {
        return orgScopes.scopeOrgIds(viewerOrg).contains(rootOrgId());
    }

    // ---- 周期集(每业务年度固定 17 项:年 1 + 季 4 + 月 12) ----

    private List<Cycle> standardCycles(int bizYear) {
        List<Cycle> list = new ArrayList<>(17);
        list.add(new Cycle(CYCLE_YEAR, String.valueOf(bizYear)));
        for (int q = 1; q <= 4; q++) {
            list.add(new Cycle(CYCLE_QUARTER, bizYear + "-Q" + q));
        }
        for (int m = 1; m <= 12; m++) {
            list.add(new Cycle(CYCLE_MONTH, String.format("%d-%02d", bizYear, m)));
        }
        return list;
    }

    private void validatePeriods(int bizYear, List<MarginPeriod> periods) {
        require(bizYear >= 2000 && bizYear <= 2100, CODE_YEAR_ILLEGAL,
                "balance.margin.year.illegal");
        require(periods != null && periods.size() == 17, CODE_PERIODS_INCOMPLETE,
                "balance.margin.periods.incomplete");
        List<Cycle> standard = standardCycles(bizYear);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MarginPeriod p : periods) {
            boolean matched = standard.stream().anyMatch(c -> c.cycleType() == p.cycleType()
                    && c.periodValue().equals(p.periodValue()));
            require(matched, CODE_PERIOD_ILLEGAL, "balance.margin.period.illegal", p.periodValue());
            require(seen.add(p.periodValue()), CODE_PERIOD_DUP, "balance.margin.period.dup",
                    p.periodValue());
            require(p.comprehensiveLine() > 0 && p.comprehensiveLine() <= 10000
                    && p.projectLine() > 0 && p.projectLine() <= 10000
                    && p.mainMaterialLine() > 0 && p.mainMaterialLine() <= 10000,
                    CODE_RATE_RANGE, "balance.margin.rate.range");
        }
    }

    /** 周期状态:未结束 PENDING(实际值待更新)/已结束 NORMAL——空态原则禁假 0 */
    private String periodStatus(int cycleType, String periodValue) {
        LocalDate now = LocalDate.now();
        int y = now.getYear(), m = now.getMonthValue();
        if (cycleType == CYCLE_YEAR) {
            return Integer.parseInt(periodValue) < y ? "NORMAL" : "PENDING";
        }
        if (cycleType == CYCLE_QUARTER) {
            String[] parts = periodValue.split("-Q");
            int py = Integer.parseInt(parts[0]), pq = Integer.parseInt(parts[1]);
            return (py < y) || (py == y && pq < (m - 1) / 3 + 1) ? "NORMAL" : "PENDING";
        }
        int pm = Integer.parseInt(periodValue.substring(5, 7));
        return Integer.parseInt(periodValue.substring(0, 4)) < y
                || (Integer.parseInt(periodValue.substring(0, 4)) == y && pm < m) ? "NORMAL" : "PENDING";
    }

    // ---- 发布 ----

    @Transactional
    public Map<String, Object> publishGroup(long viewerOrg, long operatorId, GroupPublishRequest req) {
        require(req != null && req.bizYear() != null && req.periods() != null, CODE_PARAM_EMPTY,
                "balance.margin.param.empty");
        require(isGroupIdentity(viewerOrg), CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
        validatePeriods(req.bizYear(), req.periods());
        long root = rootOrgId();
        long versionId = doPublish(root, operatorId, LINE_GROUP, req.bizYear(), req.baseVersionId(),
                req.periods(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        return result;
    }

    @Transactional
    public Map<String, Object> publishBranch(long viewerOrg, long operatorId, BranchPublishRequest req) {
        require(req != null && req.bizYear() != null && req.periods() != null, CODE_PARAM_EMPTY,
                "balance.margin.param.empty");
        validatePeriods(req.bizYear(), req.periods());
        long root = rootOrgId();
        boolean groupIdentity = isGroupIdentity(viewerOrg);
        long target;
        if (groupIdentity) {
            // 集团身份必须显式选目标组织(fl-yy 语义保留),且目标=根的直接子组织
            require(req.orgId() != null, CODE_BRANCH_TARGET_REQUIRED,
                    "balance.margin.branch.target.required");
            target = req.orgId();
        } else {
            // 分公司身份只许操作本组织
            require(req.orgId() == null || req.orgId() == viewerOrg, CODE_BRANCH_FORBIDDEN,
                    "balance.margin.branch.forbidden");
            target = viewerOrg;
        }
        List<Long> scope = orgScopes.scopeOrgIds(viewerOrg);
        require(scope.contains(target) && target != root, CODE_BRANCH_FORBIDDEN,
                "balance.margin.branch.forbidden");
        Long parent = jdbc.queryForObject("select parent_id from organization where id = ?", Long.class,
                target);
        require(parent != null && parent == root, CODE_BRANCH_FORBIDDEN,
                "balance.margin.branch.forbidden");
        // 前置:同年度集团管理线已发布且快照完整
        Long groupVersion = latestVersionId(root, req.bizYear(), LINE_GROUP);
        require(groupVersion != null, CODE_GROUP_NOT_PUBLISHED, "balance.margin.group.not.published");
        Integer groupRows = jdbc.queryForObject(
                "select count(*) from balance_margin_line_publish where version_id = ?", Integer.class,
                groupVersion);
        require(groupRows != null && groupRows == 17, CODE_GROUP_SNAPSHOT_INCOMPLETE,
                "balance.margin.group.snapshot.incomplete");
        Map<String, Map<String, Integer>> groupSnap = snapshotMap(groupVersion);
        long versionId = doPublish(target, operatorId, LINE_BRANCH, req.bizYear(), req.baseVersionId(),
                req.periods(), groupSnap);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        return result;
    }

    /**
     * 发布管道(集团/分公司同一管道):乐观并发闸 → 插版次 → 17 行快照(append-only)→
     * 与上一版本 diff(仅 before≠after 记行,首发零变更行)。发布无删除无反发布。
     */
    private long doPublish(long org, long operatorId, int lineType, int bizYear, Long baseVersionId,
            List<MarginPeriod> periods, Map<String, Map<String, Integer>> groupRef) {
        Long latest = latestVersionId(org, bizYear, lineType);
        boolean stale = (latest == null) ? baseVersionId != null : !latest.equals(baseVersionId);
        require(!stale, CODE_VERSION_STALE, "balance.margin.version.stale");

        jdbc.update("""
                insert into balance_margin_line_version(org_id, biz_year, line_type, created_by)
                values (?, ?, ?, ?)""", org, bizYear, lineType, operatorId);
        long versionId = jdbc.queryForObject("select last_insert_id()", Long.class);

        Map<String, Map<String, Integer>> prev = latest == null ? Map.of() : snapshotMap(latest);
        for (Cycle c : standardCycles(bizYear)) {
            MarginPeriod p = periods.stream()
                    .filter(x -> x.cycleType() == c.cycleType() && x.periodValue().equals(c.periodValue()))
                    .findFirst().orElseThrow();
            Map<String, Integer> ref = groupRef == null ? null : groupRef.get(c.periodValue());
            jdbc.update("""
                    insert into balance_margin_line_publish(version_id, org_id, cycle_type, period_value,
                        comprehensive_line, project_line, main_material_line,
                        group_comprehensive_ref, group_project_ref, group_main_ref)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    versionId, org, c.cycleType(), c.periodValue(),
                    p.comprehensiveLine(), p.projectLine(), p.mainMaterialLine(),
                    ref == null ? null : ref.get("comprehensive"),
                    ref == null ? null : ref.get("project"),
                    ref == null ? null : ref.get("main"));
            diffAndRecord(versionId, c, prev.get(c.periodValue()), p);
        }
        return versionId;
    }

    /** 变更明细:逐口径仅 before≠after 记行;change=after−before 读取期计算不落列 */
    private void diffAndRecord(long versionId, Cycle c, Map<String, Integer> before, MarginPeriod p) {
        recordDiff(versionId, c, RATE_COMPREHENSIVE, before == null ? null : before.get("comprehensive"),
                p.comprehensiveLine());
        recordDiff(versionId, c, RATE_PROJECT, before == null ? null : before.get("project"),
                p.projectLine());
        recordDiff(versionId, c, RATE_MAIN, before == null ? null : before.get("main"),
                p.mainMaterialLine());
    }

    private void recordDiff(long versionId, Cycle c, int rateType, Integer before, int after) {
        // 周期集固定 17 项,前版本无此周期值的情况不存在;before=null 只出现在首发(无前版本),
        // 首发没有"变化"语义——冒烟第 96 次实锤:漏判时首发会插 51 行 before=NULL 脏行(PRD:首发零变更行)
        if (before == null || before.intValue() == after) {
            return;
        }
        jdbc.update("""
                insert into balance_margin_line_change(version_id, cycle_type, period_value, rate_type,
                    before_value, after_value) values (?, ?, ?, ?, ?, ?)""",
                versionId, c.cycleType(), c.periodValue(), rateType, before, after);
    }

    private Map<String, Map<String, Integer>> snapshotMap(long versionId) {
        Map<String, Map<String, Integer>> map = new HashMap<>();
        jdbc.queryForList("""
                select period_value, comprehensive_line, project_line, main_material_line
                from balance_margin_line_publish where version_id = ?""", versionId)
                .forEach(r -> {
                    Map<String, Integer> lines = new HashMap<>();
                    lines.put("comprehensive", (Integer) r.get("comprehensive_line"));
                    lines.put("project", (Integer) r.get("project_line"));
                    lines.put("main", (Integer) r.get("main_material_line"));
                    map.put((String) r.get("period_value"), lines);
                });
        return map;
    }

    private Long latestVersionId(long org, int bizYear, int lineType) {
        List<Long> ids = jdbc.queryForList("""
                select id from balance_margin_line_version
                where org_id = ? and biz_year = ? and line_type = ? order by id desc limit 1""",
                Long.class, org, bizYear, lineType);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---- 查询面 ----

    /** 集团当前线:17 周期骨架(首发前线值 null 可编辑)+当前版本 id;守卫=scope 含根 */
    public Map<String, Object> groupLine(long viewerOrg, Integer year) {
        require(isGroupIdentity(viewerOrg), CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
        require(year != null && year >= 2000 && year <= 2100, CODE_YEAR_ILLEGAL,
                "balance.margin.year.illegal");
        return lineView(rootOrgId(), year, LINE_GROUP);
    }

    /** 分公司当前线:本公司线 + 快照行内当期集团线引用对照;守卫=目标 ∈ scope 且非根 */
    public Map<String, Object> branchLine(long viewerOrg, Long targetOrg, Integer year) {
        require(year != null && year >= 2000 && year <= 2100, CODE_YEAR_ILLEGAL,
                "balance.margin.year.illegal");
        long root = rootOrgId();
        long target = resolveBranchTarget(viewerOrg, targetOrg, root);
        return lineView(target, year, LINE_BRANCH);
    }

    private long resolveBranchTarget(long viewerOrg, Long targetOrg, long root) {
        boolean groupIdentity = isGroupIdentity(viewerOrg);
        long target;
        if (groupIdentity) {
            require(targetOrg != null, CODE_BRANCH_TARGET_REQUIRED,
                    "balance.margin.branch.target.required");
            target = targetOrg;
        } else {
            require(targetOrg == null || targetOrg == viewerOrg, CODE_BRANCH_FORBIDDEN,
                    "balance.margin.branch.forbidden");
            target = viewerOrg;
        }
        List<Long> scope = orgScopes.scopeOrgIds(viewerOrg);
        require(scope.contains(target) && target != root, CODE_BRANCH_FORBIDDEN,
                "balance.margin.branch.forbidden");
        return target;
    }

    private Map<String, Object> lineView(long org, int year, int lineType) {
        List<Map<String, Object>> periods = new ArrayList<>();
        Map<String, Map<String, Object>> published = new HashMap<>();
        Long versionId = latestVersionId(org, year, lineType);
        if (versionId != null) {
            for (Map<String, Object> r : jdbc.queryForList("""
                    select cycle_type, period_value, comprehensive_line, project_line, main_material_line,
                           group_comprehensive_ref, group_project_ref, group_main_ref,
                           actual_comprehensive_rate, actual_data_as_of
                    from balance_margin_line_publish where version_id = ?""", versionId)) {
                published.put((String) r.get("period_value"), r);
            }
            if (published.size() != 17) {
                throw new BizException(CODE_SNAPSHOT_INCOMPLETE, msg("balance.margin.snapshot.incomplete"));
            }
        }
        for (Cycle c : standardCycles(year)) {
            Map<String, Object> row = published.get(c.periodValue());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cycleType", c.cycleType());
            item.put("periodValue", c.periodValue());
            item.put("periodStatus", periodStatus(c.cycleType(), c.periodValue()));
            item.put("comprehensiveLine", row == null ? null : row.get("comprehensive_line"));
            item.put("projectLine", row == null ? null : row.get("project_line"));
            item.put("mainMaterialLine", row == null ? null : row.get("main_material_line"));
            item.put("groupComprehensiveRef", row == null ? null : row.get("group_comprehensive_ref"));
            item.put("groupProjectRef", row == null ? null : row.get("group_project_ref"));
            item.put("groupMainRef", row == null ? null : row.get("group_main_ref"));
            item.put("actualComprehensiveRate", row == null ? null : row.get("actual_comprehensive_rate"));
            item.put("actualDataAsOf", row == null ? null : row.get("actual_data_as_of"));
            periods.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("bizYear", year);
        result.put("lineType", lineType);
        result.put("periods", periods);
        return result;
    }

    private Map<String, Object> versions(long viewerOrg, Long orgId, Integer year, int lineType,
            int page, int size) {
        require(year != null && year >= 2000 && year <= 2100, CODE_YEAR_ILLEGAL,
                "balance.margin.year.illegal");
        long root = rootOrgId();
        long org;
        if (lineType == LINE_GROUP) {
            require(isGroupIdentity(viewerOrg), CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
            org = root;
        } else {
            org = resolveBranchTarget(viewerOrg, orgId, root);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Integer count = jdbc.queryForObject("""
                select count(*) from balance_margin_line_version
                where org_id = ? and biz_year = ? and line_type = ?""", Integer.class, org, year, lineType);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.org_id, o.name as org_name, v.biz_year, v.line_type,
                       v.created_by, u.display_name as created_by_name, v.created_at,
                       (select count(*) from balance_margin_line_change c where c.version_id = v.id) as change_count
                from balance_margin_line_version v
                left join organization o on o.id = v.org_id
                left join yz_user u on u.id = v.created_by
                where v.org_id = ? and v.biz_year = ? and v.line_type = ?
                order by v.id desc limit ? offset ?""",
                org, year, lineType, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> groupVersions(long viewerOrg, Integer year, int page, int size) {
        return versions(viewerOrg, null, year, LINE_GROUP, page, size);
    }

    public Map<String, Object> branchVersions(long viewerOrg, Long orgId, Integer year, int page,
            int size) {
        return versions(viewerOrg, orgId, year, LINE_BRANCH, page, size);
    }

    /** 分公司选项+配置进度:根直接子组织(启用)逐个看该年最新分公司版本——
     *  发布制无草稿态,进度二态 NONE/COMPLETED(PARTIAL 枚举位保留,V1 不可达) */
    public Map<String, Object> companyOptions(long viewerOrg, Integer year, String keyword) {
        require(year != null && year >= 2000 && year <= 2100, CODE_YEAR_ILLEGAL,
                "balance.margin.year.illegal");
        long root = rootOrgId();
        require(isGroupIdentity(viewerOrg), CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
        List<Object> args = new ArrayList<>(List.of(root));
        StringBuilder w = new StringBuilder(" where parent_id = ? and status = 0");
        if (keyword != null && !keyword.isBlank()) {
            w.append(" and name like ?");
            args.add("%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> orgs = jdbc.queryForList(
                "select id, name from organization" + w + " order by id", args.toArray());
        int none = 0, partial = 0, completed = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> o : orgs) {
            Long orgId = ((Number) o.get("id")).longValue();
            Long latest = latestVersionId(orgId, year, LINE_BRANCH);
            String configStatus = latest == null ? "NONE" : "COMPLETED";
            if ("NONE".equals(configStatus)) {
                none++;
            } else {
                completed++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orgId", orgId);
            item.put("name", o.get("name"));
            item.put("configStatus", configStatus);
            item.put("versionId", latest);
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", list.size());
        result.put("none", none);
        result.put("partial", partial);
        result.put("completed", completed);
        result.put("rows", list);
        return result;
    }

    /** 版本修改详情:变更明细行(周期×口径 before/after/change);历史版本只读 */
    public Map<String, Object> versionDetail(long viewerOrg, long versionId) {
        List<Map<String, Object>> heads = jdbc.queryForList("""
                select v.id, v.org_id, o.name as org_name, v.biz_year, v.line_type, v.created_by,
                       u.display_name as created_by_name, v.created_at
                from balance_margin_line_version v
                left join organization o on o.id = v.org_id
                left join yz_user u on u.id = v.created_by
                where v.id = ?""", versionId);
        require(!heads.isEmpty(), CODE_VERSION_NOT_FOUND, "balance.margin.version.not.found");
        Map<String, Object> head = heads.get(0);
        int lineType = ((Number) head.get("line_type")).intValue();
        long orgId = ((Number) head.get("org_id")).longValue();
        if (lineType == LINE_GROUP) {
            require(isGroupIdentity(viewerOrg), CODE_GROUP_FORBIDDEN, "balance.margin.group.forbidden");
        } else if (lineType == LINE_BRANCH) {
            long root = rootOrgId();
            List<Long> scope = orgScopes.scopeOrgIds(viewerOrg);
            require(scope.contains(orgId) && orgId != root, CODE_BRANCH_FORBIDDEN,
                    "balance.margin.branch.forbidden");
        } else {
            throw new BizException(CODE_VERSION_TYPE_ILLEGAL, msg("balance.margin.version.type.illegal"));
        }
        List<Map<String, Object>> changes = jdbc.queryForList("""
                select cycle_type, period_value, rate_type, before_value, after_value,
                       (after_value - before_value) as change_value
                from balance_margin_line_change where version_id = ?
                order by period_value, rate_type""", versionId);
        head.put("changes", changes);
        return head;
    }
}
