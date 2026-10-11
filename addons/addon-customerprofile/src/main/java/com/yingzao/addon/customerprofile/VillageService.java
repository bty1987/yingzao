package com.yingzao.addon.customerprofile;

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
 * 楼盘档案服务:城市/城区字典 + 楼盘(树)维护与检索。
 * 老系统教训落地:addVillage 无查重 → (org,城区,名称) DB 唯一键兜底 + 友好业务码;
 * 编号取号读改写竞态 → 建档后按 id 生成(V+6位),无计数器;
 * 「全部」=「启用」条件缺陷 → status 不传 = 不过滤,传 0/1 才过滤。
 */
@Service
public class VillageService {

    public static final int CODE_VILLAGE_NOT_FOUND = 330001;
    public static final int CODE_VILLAGE_EXISTS = 330002;
    public static final int CODE_CITY_NOT_FOUND = 330013;
    public static final int CODE_PROPER_NOT_FOUND = 330014;
    public static final int CODE_PROPER_EXISTS = 330015;
    public static final int CODE_CITY_EXISTS = 330016;
    public static final int CODE_INVALID_PARAM = 330017;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public VillageService(JdbcTemplate jdbc, MessageSource messages) {
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

    // ---- 城市 / 城区 ----

    public List<Map<String, Object>> cities() {
        List<Map<String, Object>> cityRows = jdbc.queryForList(
                "select id, name from customerprofile_city order by id");
        List<Map<String, Object>> properRows = jdbc.queryForList(
                "select id, city_id, name from customerprofile_city_proper order by id");
        for (Map<String, Object> city : cityRows) {
            city.put("propers", properRows.stream()
                    .filter(p -> ((Number) p.get("city_id")).longValue() == ((Number) city.get("id")).longValue())
                    .map(p -> Map.of("id", ((Number) p.get("id")).longValue(), "name", p.get("name")))
                    .toList());
        }
        return cityRows;
    }

    public long addCity(String name) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        try {
            jdbc.update("insert into customerprofile_city(name) values (?)", name.trim());
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_CITY_EXISTS, msg("city.exists"));
        }
        return jdbc.queryForObject("select id from customerprofile_city where name = ? limit 1", Long.class, name.trim());
    }

    public long addProper(long cityId, String name) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(cityExists(cityId), CODE_CITY_NOT_FOUND, "city.notfound");
        try {
            jdbc.update("insert into customerprofile_city_proper(city_id, name) values (?,?)", cityId, name.trim());
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PROPER_EXISTS, msg("proper.exists"));
        }
        return jdbc.queryForObject(
                "select id from customerprofile_city_proper where city_id = ? and name = ? limit 1",
                Long.class, cityId, name.trim());
    }

    private boolean cityExists(long cityId) {
        Integer count = jdbc.queryForObject("select count(*) from customerprofile_city where id = ?", Integer.class, cityId);
        return count != null && count > 0;
    }

    // ---- 楼盘 ----

    @Transactional
    public long create(long orgId, String name, long cityId, long properId, Long parentId,
                       String developer, String deliveryTime, Integer x, Integer y, Integer mileage, String content) {
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(cityExists(cityId), CODE_CITY_NOT_FOUND, "city.notfound");
        Map<String, Object> proper = jdbc.queryForMap(
                "select id, city_id from customerprofile_city_proper where id = ?", properId);
        if (((Number) proper.get("city_id")).longValue() != cityId) {
            throw new BizException(CODE_PROPER_NOT_FOUND, msg("proper.notfound"));
        }
        if (parentId != null && !villageExists(orgId, parentId)) {
            throw new BizException(CODE_VILLAGE_NOT_FOUND, msg("village.notfound"));
        }
        try {
            jdbc.update("""
                    insert into customerprofile_village
                        (org_id, no, name, city_id, city_proper_id, parent_id, developer, delivery_time,
                         x, y, mileage, content, status)
                    values (?, concat('P', left(md5(uuid()), 12)), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, orgId, name.trim(), cityId, properId, parentId, developer, deliveryTime,
                    x == null ? 0 : x, y == null ? 0 : y, mileage == null ? 0 : mileage, content, STATUS_ENABLED);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            // 编号按 id 生成,无计数器读改写竞态(老系统 counting 取号教训)
            jdbc.update("update customerprofile_village set no = concat('V', lpad(id, 6, '0')) where id = ?", id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_VILLAGE_EXISTS, msg("village.exists"));
        }
    }

    private boolean villageExists(long orgId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_village where id = ? and org_id in (0, ?)",
                Integer.class, id, orgId);
        return count != null && count > 0;
    }

    @Transactional
    public void update(long orgId, long id, String name, Long properId, Long cityId, Long parentId,
                       String developer, String deliveryTime, Integer x, Integer y, Integer mileage, String content) {
        Map<String, Object> village = load(orgId, id);
        String finalName = name != null && !name.isBlank() ? name.trim() : (String) village.get("name");
        long finalProper = properId != null ? properId : ((Number) village.get("city_proper_id")).longValue();
        long finalCity = cityId != null ? cityId : ((Number) village.get("city_id")).longValue();
        Map<String, Object> proper = jdbc.queryForMap(
                "select id, city_id from customerprofile_city_proper where id = ?", finalProper);
        if (((Number) proper.get("city_id")).longValue() != finalCity) {
            throw new BizException(CODE_PROPER_NOT_FOUND, msg("proper.notfound"));
        }
        if (parentId != null && parentId != id && !villageExists(orgId, parentId)) {
            throw new BizException(CODE_VILLAGE_NOT_FOUND, msg("village.notfound"));
        }
        try {
            jdbc.update("""
                    update customerprofile_village set name = ?, city_id = ?, city_proper_id = ?, parent_id = ?,
                        developer = ?, delivery_time = ?, x = ?, y = ?, mileage = ?, content = ?
                    where id = ? and org_id = ?
                    """, finalName, finalCity, finalProper,
                    parentId != null ? parentId : village.get("parent_id"),
                    developer != null ? developer : village.get("developer"),
                    deliveryTime != null ? deliveryTime : village.get("delivery_time"),
                    x != null ? x : village.get("x"),
                    y != null ? y : village.get("y"),
                    mileage != null ? mileage : village.get("mileage"),
                    content != null ? content : village.get("content"),
                    id, orgId);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_VILLAGE_EXISTS, msg("village.exists"));
        }
    }

    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED, CODE_INVALID_PARAM, "param.invalid");
        int updated = jdbc.update(
                "update customerprofile_village set status = ? where id = ? and org_id = ?", status, id, orgId);
        if (updated == 0) {
            throw new BizException(CODE_VILLAGE_NOT_FOUND, msg("village.notfound"));
        }
    }

    public Map<String, Object> page(long orgId, String keyword, Long properId, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where v.org_id in (0, ?)");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and v.name like concat('%', ?, '%')");
            args.add(keyword.trim());
        }
        if (properId != null) {
            where.append(" and v.city_proper_id = ?");
            args.add(properId);
        }
        if (status != null) {
            where.append(" and v.status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from customerprofile_village v" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.no, v.name, v.city_id, v.city_proper_id, p.name as proper_name,
                       c.name as city_name, v.parent_id, v.developer, v.delivery_time,
                       v.x, v.y, v.mileage, v.content, v.status, v.created_at
                from customerprofile_village v
                join customerprofile_city_proper p on p.id = v.city_proper_id
                join customerprofile_city c on c.id = v.city_id
                """ + where + " order by v.id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> get(long orgId, long id) {
        return load(orgId, id);
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.no, v.name, v.city_id, v.city_proper_id, p.name as proper_name,
                       c.name as city_name, v.parent_id, v.developer, v.delivery_time,
                       v.x, v.y, v.mileage, v.content, v.status, v.created_at, v.updated_at
                from customerprofile_village v
                join customerprofile_city_proper p on p.id = v.city_proper_id
                join customerprofile_city c on c.id = v.city_id
                where v.id = ? and v.org_id in (0, ?)
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_VILLAGE_NOT_FOUND, msg("village.notfound"));
        }
        return rows.get(0);
    }
}
