package com.yingzao.core.org;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 组织树(内核平台设施,docs/08 组织模型裁决落地):一棵自引用树——
 * 公司→分区→部门→(子部门…),层级是深度标签不是独立实体,不做「集团/分公司」两类公司实体。
 *
 * <p>关键语义:①org_path 物化路径(含自身,如 /1/5/12/),子树查询一条 like 免递归;
 * ②历史 org(建表前已存在的 org_id 数值)首次触达懒建根节点,全部既有数据零迁移;
 * ③停用=子树整体停(级联),启用只恢复本节点(子节点逐个手动启用);
 * ④数据范围基座:scopeOrgIds(登录节点)=自身+子树 id 集,供 S2 数据范围与 S3 目标聚合复用。
 *
 * <p>错误码段 120106-120110(120100 用户管理族既有)。
 */
@Service
public class OrganizationService {

    public static final int CODE_PARENT_NOT_FOUND = 120107;
    public static final int CODE_DUP_NAME = 120108;
    public static final int CODE_ROOT_UNDISABLE = 120109;
    public static final int CODE_NOT_FOUND = 120110;

    public static final String TYPE_COMPANY = "company";
    public static final String TYPE_REGION = "region";
    public static final String TYPE_DEPARTMENT = "department";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public OrganizationService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }

    // ---- 查询 ----

    /** 组织行(懒建根:建表前已存在的 org_id 首次触达自动补根节点,零迁移)。 */
    public Map<String, Object> rowOf(long orgId) {
        ensureRoot(orgId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, parent_id, name, level_type, org_path, status, created_at"
                        + " from organization where id = ?", orgId);
        return rows.get(0);
    }

    /** 全树(启用+停用,管理页渲染;根列表+children 嵌套)。 */
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> all = jdbc.queryForList("""
                select id, parent_id, name, level_type, org_path, status, created_at
                from organization order by id
                """);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : all) {
            r.put("children", new ArrayList<Map<String, Object>>());
            byId.put(((Number) r.get("id")).longValue(), r);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Object pid = r.get("parent_id");
            Map<String, Object> parent = pid == null ? null : byId.get(((Number) pid).longValue());
            if (parent != null && parent != r) {
                ((List<Map<String, Object>>) parent.get("children")).add(r);
            } else {
                roots.add(r);
            }
        }
        return roots;
    }

    /** 数据范围基座:登录节点可见组织 id 集 = 自身 + 子树(S2 查询基座/S3 聚合复用)。 */
    public List<Long> scopeOrgIds(long orgId) {
        ensureRoot(orgId);
        String path = jdbc.queryForObject(
                "select org_path from organization where id = ?", String.class, orgId);
        return jdbc.queryForList(
                "select id from organization where org_path like ? and status = 0",
                Long.class, path + "%");
    }

    // ---- 管理 ----

    public record CreateBody(Long parentId, String name, String levelType) {
    }

    /** 建子组织:父须存在且启用;同级名称唯一;path = 父 path + id + /。 */
    @Transactional
    public long create(long operatorOrgId, CreateBody body, long operatorId) {
        require(body != null && body.name() != null && !body.name().isBlank(), CODE_NOT_FOUND, "org.notFound");
        String name = body.name().trim();
        String levelType = body.levelType() == null || body.levelType().isBlank()
                ? TYPE_DEPARTMENT : body.levelType().trim();
        require(levelType.equals(TYPE_COMPANY) || levelType.equals(TYPE_REGION)
                || levelType.equals(TYPE_DEPARTMENT), CODE_NOT_FOUND, "org.notFound");
        Long parentId = body.parentId();
        require(parentId != null && parentId > 0, CODE_PARENT_NOT_FOUND, "org.parentNotFound");
        rowOf(parentId); // 懒建根
        Map<String, Object> parent = jdbc.queryForList(
                "select org_path, status from organization where id = ?", parentId).get(0);
        require(((Number) parent.get("status")).intValue() == 0, CODE_PARENT_NOT_FOUND, "org.parentNotFound");
        Integer dup = jdbc.queryForObject(
                "select count(*) from organization where parent_id = ? and name = ?",
                Integer.class, parentId, name);
        require(dup == null || dup == 0, CODE_DUP_NAME, "org.dupName");
        jdbc.update("""
                insert into organization(parent_id, name, level_type, org_path, status)
                values (?, ?, ?, 'pending', 0)
                """, parentId, name, levelType);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("update organization set org_path = concat(?, ?, '/') where id = ?",
                parent.get("org_path"), id, id);
        return id;
    }

    public record UpdateBody(String name, String levelType) {
    }

    /** 改名/改标签:仅本节点,不动 path(移动 V1 不做,docs/08 显式低频排除)。 */
    @Transactional
    public void update(long orgId, long id, UpdateBody body) {
        rowOf(orgId);
        Map<String, Object> node = load(id);
        require(((Number) node.get("status")).intValue() == 0, CODE_NOT_FOUND, "org.notFound");
        String name = body == null || body.name() == null ? "" : body.name().trim();
        require(!name.isBlank(), CODE_NOT_FOUND, "org.notFound");
        String levelType = body == null || body.levelType() == null
                || body.levelType().isBlank() ? (String) node.get("level_type") : body.levelType().trim();
        Integer dup = jdbc.queryForObject(
                "select count(*) from organization where parent_id = ? and name = ? and id != ?",
                Integer.class, node.get("parent_id"), name, id);
        require(dup == null || dup == 0, CODE_DUP_NAME, "org.dupName");
        jdbc.update("update organization set name = ?, level_type = ? where id = ?",
                name, levelType, id);
    }

    /** 停用=子树整体停(级联);启用只恢复本节点;根不可停(组织停用走用户停用)。 */
    @Transactional
    public void changeStatus(long orgId, long id, int status) {
        rowOf(orgId);
        Map<String, Object> node = load(id);
        require(status == 0 || status == 1, CODE_NOT_FOUND, "org.notFound");
        require(!(status == 1 && node.get("parent_id") == null), CODE_ROOT_UNDISABLE, "org.rootUndisable");
        jdbc.update("update organization set status = ? where id = ?", status, id);
        if (status == 1) {
            jdbc.update("update organization set status = 1 where org_path like ? and id != ?",
                    node.get("org_path") + "%", id);
        }
    }

    // ---- 内部 ----

    /** 懒建根:org 行不存在时按根初始化(路径 /id/,启用)——历史 org 零迁移的关键。 */
    private void ensureRoot(long orgId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from organization where id = ?", Integer.class, orgId);
        if (n == null || n == 0) {
            jdbc.update("""
                    insert ignore into organization(id, parent_id, name, level_type, org_path, status)
                    values (?, null, ?, 'company', concat('/', ?, '/'), 0)
                    """, orgId, msg("org.defaultName") + orgId, orgId);
        }
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, parent_id, name, level_type, org_path, status from organization where id = ?", id);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "org.notFound");
        return rows.get(0);
    }
}
