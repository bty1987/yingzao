package com.yingzao.addon.basedata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 品类树服务:老 goodsKind 四级定长挂列(zerokind~thrkind)改单表树。
 * 平台共享(org_id=0)+ 组织私有两层;根节点 parent_id=0(MySQL 唯一索引不约束 NULL,
 * 用 0 让「同组织同父同名」约束对根品类同样成立);停用守卫=存在启用子品类须先停用子品类。
 */
@Service
public class CategoryService {

    public static final int CODE_CATEGORY_NOT_FOUND = 260002;
    public static final int CODE_CATEGORY_EXISTS = 260003;
    public static final int CODE_CATEGORY_HAS_ENABLED_CHILDREN = 260004;
    public static final int CODE_INVALID_PARAM = 260001;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    public static final long ROOT = 0L;
    public static final long ORG_PLATFORM = 0L;

    private final JdbcTemplate jdbc;
    private final org.springframework.context.MessageSource messages;

    public CategoryService(JdbcTemplate jdbc, org.springframework.context.MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null,
                org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    @Transactional
    public long create(long orgId, String name, long parentId) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        String trimmed = name.trim();
        int level = 1;
        if (parentId != ROOT) {
            Map<String, Object> parent = load(orgId, parentId);
            level = ((Number) parent.get("level")).intValue() + 1;
        }
        try {
            jdbc.update("insert into basedata_category (org_id, parent_id, name, level) values (?,?,?,?)",
                    orgId, parentId, trimmed, level);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_CATEGORY_EXISTS, msg("category.exists"));
        }
        return jdbc.queryForObject("""
                select id from basedata_category where org_id = ? and parent_id = ? and name = ?
                """, Long.class, orgId, parentId, trimmed);
    }

    /** 改名(父挂载 V1 不做,移挂载等于重建);可见含平台行,变更仅限本组织行(V1 无平台字典写入入口)。 */
    @Transactional
    public void rename(long orgId, long id, String name) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        load(orgId, id);
        int updated;
        try {
            updated = jdbc.update("update basedata_category set name = ? where id = ? and org_id = ?",
                    name.trim(), id, orgId);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_CATEGORY_EXISTS, msg("category.exists"));
        }
        if (updated == 0) {
            // 平台共享行(org=0)组织侧不可变更
            throw new BizException(CODE_CATEGORY_NOT_FOUND, msg("category.notfound"));
        }
    }

    @Transactional
    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED, CODE_INVALID_PARAM, "param.invalid");
        load(orgId, id);
        if (status == STATUS_DISABLED) {
            Integer children = jdbc.queryForObject("""
                    select count(*) from basedata_category
                    where parent_id = ? and status = ? and org_id in (?, ?)
                    """, Integer.class, id, STATUS_ENABLED, ORG_PLATFORM, orgId);
            if (children != null && children > 0) {
                throw new BizException(CODE_CATEGORY_HAS_ENABLED_CHILDREN, msg("category.hasEnabledChildren"));
            }
        }
        int updated = jdbc.update("update basedata_category set status = ? where id = ? and org_id = ?",
                status, id, orgId);
        if (updated == 0) {
            throw new BizException(CODE_CATEGORY_NOT_FOUND, msg("category.notfound"));
        }
    }

    /** 品类树:平台共享 + 本组织私有合并(平台行在前,id 升序)。 */
    public List<Map<String, Object>> tree(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, parent_id, name, level, status
                from basedata_category
                where org_id = ? or org_id = ?
                order by org_id desc, id
                """, orgId, ORG_PLATFORM);
        Map<Long, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            row.put("children", new ArrayList<Map<String, Object>>());
            index.put(((Number) row.get("id")).longValue(), row);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long parentId = ((Number) row.get("parent_id")).longValue();
            Map<String, Object> parent = index.get(parentId);
            if (parentId == ROOT || parent == null || parent == row) {
                roots.add(row);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(row);
            }
        }
        return roots;
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, parent_id, name, level, status
                from basedata_category where id = ? and org_id in (?, ?)
                """, id, ORG_PLATFORM, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CATEGORY_NOT_FOUND, msg("category.notfound"));
        }
        return rows.get(0);
    }
}
