package com.yingzao.addon.construction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 工程档案(老 drawlineprofile/constructionprofile 复制粘贴双胞胎合一):mount 挂载点区分
 * (0 放线档案/1 施工档案/2 验收检查项模板),order_id 为空 = 组织模板行,实例由模板拷贝生成
 * (快照纪律:模板后改不影响已实例化档案)。修老缺陷:handle 逐项回写无归属校验(归属+org 双校验)、
 * status=2 已完成无写入点(显式 complete 端点,必填项校验)、make 实例化 option 恒勾选(实例从零勾)。
 */
@Service
public class ConstructionProfileService {

    /** 挂载点。 */
    public static final int MOUNT_DRAWLINE = 0;
    public static final int MOUNT_CONSTRUCTION = 1;
    public static final int MOUNT_ACCEPTANCE = 2;
    private static final Set<Integer> MOUNTS = Set.of(MOUNT_DRAWLINE, MOUNT_CONSTRUCTION, MOUNT_ACCEPTANCE);
    /** 档案项类型:0 文本域/1 输入框/2 单选/3 多选/4 日期(文本/输入/日期无选项)。 */
    private static final Set<Integer> ITEM_TYPES = Set.of(0, 1, 2, 3, 4);
    private static final Set<Integer> OPTION_TYPES = Set.of(2, 3);
    /** 状态:0 初始/1 已发送(实例化)/2 已完成。 */
    public static final int P_INIT = 0;
    public static final int P_SENT = 1;
    public static final int P_DONE = 2;

    private final JdbcTemplate jdbc;
    private final ConstructionTeamService.MessageSourceAdapter m;

    public ConstructionProfileService(JdbcTemplate jdbc,
            ConstructionTeamService.MessageSourceAdapter m) {
        this.jdbc = jdbc;
        this.m = m;
    }

    // ---- 模板 ----

    public record ItemCmd(String name, Integer type, Integer require, String content, Integer sort,
            List<String> optionTitles) {
    }

    public List<Map<String, Object>> listTemplates(long orgId, Integer mount) {
        StringBuilder where = new StringBuilder(
                " where org_id = ? and order_id is null");
        if (mount != null) {
            require(MOUNTS.contains(mount), ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
            where.append(" and mount = ").append(mount);
        }
        List<Map<String, Object>> templates = jdbc.queryForList("""
                select id, mount, name, status, created_at from construction_profile
                """ + where + " order by mount, id", orgId);
        for (Map<String, Object> template : templates) {
            template.put("items", listItems(((Number) template.get("id")).longValue()));
        }
        return templates;
    }

    @Transactional
    public long createTemplate(long orgId, Integer mount, String name, long operatorId) {
        require(MOUNTS.contains(mount) && name != null && !name.isBlank() && name.trim().length() <= 64,
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        Integer dup = jdbc.queryForObject("""
                select count(*) from construction_profile
                where org_id = ? and mount = ? and order_id is null and name = ?
                """, Integer.class, orgId, mount, name.trim());
        require(dup == null || dup == 0, ConstructionService.CODE_NODE_DUP, m.msg("construction.node.dup"));
        jdbc.update("""
                insert into construction_profile(org_id, mount, name, order_id, status, created_by)
                values (?,?,?,null,?,?)
                """, orgId, mount, name.trim(), P_INIT, operatorId);
        return jdbc.queryForObject("""
                select id from construction_profile where org_id = ? and mount = ? and order_id is null
                    and name = ?
                """, Long.class, orgId, mount, name.trim());
    }

    @Transactional
    public void deleteTemplate(long orgId, long templateId) {
        loadProfile(orgId, templateId, true);
        jdbc.update("delete from construction_profile_option where item_id in "
                + "(select id from construction_profile_item where profile_id = ?)", templateId);
        jdbc.update("delete from construction_profile_item where profile_id = ?", templateId);
        jdbc.update("delete from construction_profile where id = ?", templateId);
    }

    @Transactional
    public long addTemplateItem(long orgId, long templateId, ItemCmd cmd) {
        Map<String, Object> profile = loadProfile(orgId, templateId, true);
        require(((Number) profile.get("order_id")) == null, ConstructionService.CODE_PROFILE_ILLEGAL,
                m.msg("construction.profile.illegal"));
        return insertItem(templateId, cmd);
    }

    @Transactional
    public void deleteTemplateItem(long orgId, long templateId, long itemId) {
        loadProfile(orgId, templateId, true);
        jdbc.update("delete from construction_profile_option where item_id = ?", itemId);
        jdbc.update("delete from construction_profile_item where id = ? and profile_id = ?", itemId,
                templateId);
    }

    // ---- 实例 ----

    /** 实例化:模板 → 本单档案拷贝(头+项+选项全量快照,实例选项从零勾选)。 */
    @Transactional
    public long instantiate(long orgId, long orderId, long templateId, long operatorId) {
        Map<String, Object> template = loadProfile(orgId, templateId, true);
        jdbc.update("""
                insert into construction_profile(org_id, mount, name, order_id, status, created_by)
                select org_id, mount, name, ?, ?, ? from construction_profile where id = ?
                """, orderId, P_SENT, operatorId, templateId);
        long instanceId = jdbc.queryForObject("""
                select id from construction_profile where org_id = ? and order_id = ?
                    and name = ? order by id desc limit 1
                """, Long.class, orgId, orderId, template.get("name"));
        jdbc.update("""
                insert into construction_profile_item(profile_id, name, type, `require`, content, sort)
                select ?, name, type, `require`, content, sort from construction_profile_item
                where profile_id = ? order by sort
                """, instanceId, templateId);
        jdbc.update("""
                insert into construction_profile_option(item_id, title, checked, sort)
                select i2.id, o.title, 0, o.sort
                from construction_profile_item i1
                join construction_profile_item i2 on i2.profile_id = ? and i2.sort = i1.sort
                    and i2.name = i1.name
                join construction_profile_option o on o.item_id = i1.id
                where i1.profile_id = ?
                """, instanceId, templateId);
        return instanceId;
    }

    public List<Map<String, Object>> listOrderProfiles(long orgId, long orderId) {
        List<Map<String, Object>> profiles = jdbc.queryForList("""
                select id, mount, name, status, created_at from construction_profile
                where org_id = ? and order_id = ? order by mount, id
                """, orgId, orderId);
        for (Map<String, Object> profile : profiles) {
            profile.put("items", listItems(((Number) profile.get("id")).longValue()));
        }
        return profiles;
    }

    /** 逐项回写(归属校验:档案须本组织实例,项须属本档案;单选/多选按选项 id 全量置勾)。 */
    @Transactional
    public void fillItem(long orgId, long profileId, long itemId, String content, List<Long> checkedOptionIds) {
        Map<String, Object> profile = loadProfile(orgId, profileId, false);
        require(profile.get("order_id") != null, ConstructionService.CODE_PROFILE_ILLEGAL,
                m.msg("construction.profile.illegal"));
        Integer type = jdbc.queryForObject(
                "select type from construction_profile_item where id = ? and profile_id = ?",
                Integer.class, itemId, profileId);
        if (type == null) {
            throw new BizException(ConstructionService.CODE_NOT_FOUND, m.msg("construction.notfound"));
        }
        if (OPTION_TYPES.contains(type)) {
            Set<Long> checked = new HashSet<>(checkedOptionIds == null ? List.of() : checkedOptionIds);
            List<Map<String, Object>> options = jdbc.queryForList(
                    "select id from construction_profile_option where item_id = ?", itemId);
            for (Map<String, Object> option : options) {
                long optionId = ((Number) option.get("id")).longValue();
                jdbc.update("update construction_profile_option set checked = ? where id = ?",
                        checked.contains(optionId) ? 1 : 0, optionId);
            }
        } else {
            require(checkedOptionIds == null || checkedOptionIds.isEmpty(),
                    ConstructionService.CODE_PROFILE_ILLEGAL, m.msg("construction.profile.illegal"));
            jdbc.update("update construction_profile_item set content = ? where id = ?",
                    content == null || content.isBlank() ? null : content.trim(), itemId);
        }
    }

    /** 完成(补全老 status=2 无写入点缺陷):必填项全部有值才可置 2。 */
    @Transactional
    public void complete(long orgId, long profileId) {
        Map<String, Object> profile = loadProfile(orgId, profileId, false);
        require(profile.get("order_id") != null, ConstructionService.CODE_PROFILE_ILLEGAL,
                m.msg("construction.profile.illegal"));
        List<Map<String, Object>> missing = jdbc.queryForList("""
                select i.id, i.name from construction_profile_item i
                where i.profile_id = ? and i.`require` = 1
                  and (i.content is null or i.content = '')
                  and not exists (select 1 from construction_profile_option o
                      where o.item_id = i.id and o.checked = 1)
                """, profileId);
        require(missing.isEmpty(), ConstructionService.CODE_PROFILE_REQUIRED,
                m.msgArgs("construction.profile.missing",
                        missing.isEmpty() ? "" : missing.get(0).get("name")));
        jdbc.update("update construction_profile set status = ? where id = ?", P_DONE, profileId);
    }

    // ---- 内部 ----

    /** cond 不满足即抛;第三个参数为已解析文案(调用方用 m.msg(...) 传入)。 */
    private void require(boolean cond, int code, String message) {
        if (!cond) {
            throw new BizException(code, message);
        }
    }

    private List<Map<String, Object>> listItems(long profileId) {
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, name, type, `require`, content, sort from construction_profile_item
                where profile_id = ? order by sort, id
                """, profileId);
        for (Map<String, Object> item : items) {
            item.put("options", jdbc.queryForList("""
                    select id, title, checked, sort from construction_profile_option
                    where item_id = ? order by sort, id
                    """, item.get("id")));
        }
        return items;
    }

    /** 归属校验:档案必须存在且属本组织;template=true 时要求 order_id 为空(模板行)。 */
    private Map<String, Object> loadProfile(long orgId, long profileId, boolean template) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, mount, name, order_id, status from construction_profile
                where id = ? and org_id = ?
                """, profileId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(ConstructionService.CODE_NOT_FOUND, m.msg("construction.notfound"));
        }
        Map<String, Object> row = rows.get(0);
        if (template) {
            require(row.get("order_id") == null, ConstructionService.CODE_PROFILE_ILLEGAL,
                    m.msg("construction.profile.illegal"));
        }
        return new LinkedHashMap<>(row);
    }

    private long insertItem(long profileId, ItemCmd cmd) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank() && cmd.name().trim().length() <= 200,
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        require(cmd.type() != null && ITEM_TYPES.contains(cmd.type()),
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        boolean withOptions = OPTION_TYPES.contains(cmd.type());
        require(!withOptions || (cmd.optionTitles() != null && !cmd.optionTitles().isEmpty()),
                ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
        int sort = cmd.sort() == null ? 0 : cmd.sort();
        jdbc.update("""
                insert into construction_profile_item(profile_id, name, type, `require`, content, sort)
                values (?,?,?,?,?,?)
                """, profileId, cmd.name().trim(), cmd.type(),
                Integer.valueOf(1).equals(cmd.require()) ? 1 : 0, cmd.content(), sort);
        long itemId = jdbc.queryForObject("select last_insert_id()", Long.class);
        if (withOptions) {
            for (int i = 0; i < cmd.optionTitles().size(); i++) {
                String title = cmd.optionTitles().get(i);
                require(title != null && !title.isBlank() && title.trim().length() <= 200,
                        ConstructionService.CODE_PARAM, m.msg("construction.param.invalid"));
                jdbc.update("""
                        insert into construction_profile_option(item_id, title, checked, sort)
                        values (?,?,0,?)
                        """, itemId, title.trim(), i + 1);
            }
        }
        return itemId;
    }
}
