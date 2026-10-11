package com.yingzao.addon.basedata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 品牌字典服务:行业公共字典,无组织维度(老 communion.brand;供应商/代理商关系归 addon-supplier)。
 * 名称全局唯一;编号按 id 生成(B+6 位),灭老系统 counting 取号竞态。
 */
@Service
public class BrandService {

    public static final int CODE_BRAND_NOT_FOUND = 260005;
    public static final int CODE_BRAND_EXISTS = 260006;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public BrandService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    @Transactional
    public long create(String name, String pinyin) {
        require(name != null && !name.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        try {
            // 编号按 id 生成(md5 占位防并发撞号),无 counting 读改写竞态(老系统取号教训)
            jdbc.update("insert into basedata_brand (no, name, pinyin) values (concat('B', left(md5(uuid()), 6)), ?, ?)",
                    name.trim(), blankToNull(pinyin));
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            jdbc.update("update basedata_brand set no = concat('B', lpad(id, 6, '0')) where id = ?", id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_BRAND_EXISTS, msg("brand.exists"));
        }
    }

    public List<Map<String, Object>> list(String keyword, boolean includeDisabled) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (name like concat('%', ?, '%') or pinyin like concat('%', ?, '%'))");
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        if (!includeDisabled) {
            where.append(" and status = ?");
            args.add(STATUS_ENABLED);
        }
        return jdbc.queryForList("select id, no, name, pinyin, status from basedata_brand" + where
                + " order by id desc limit 200", args.toArray());
    }

    @Transactional
    public void rename(long id, String name) {
        require(name != null && !name.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(id);
        try {
            jdbc.update("update basedata_brand set name = ? where id = ?", name.trim(), id);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_BRAND_EXISTS, msg("brand.exists"));
        }
    }

    @Transactional
    public void updateStatus(long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(id);
        jdbc.update("update basedata_brand set status = ? where id = ?", status, id);
    }

    private void load(long id) {
        Integer count = jdbc.queryForObject("select count(*) from basedata_brand where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new BizException(CODE_BRAND_NOT_FOUND, msg("brand.notfound"));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Map<String, Object> brief(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("name", row.get("name"));
        return m;
    }
}
