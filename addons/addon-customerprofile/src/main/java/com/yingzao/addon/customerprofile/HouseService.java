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

import com.yingzao.addons.api.event.HouseCreatedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.security.CryptoBox;
import com.yingzao.core.web.BizException;

/**
 * 房屋档案服务:门牌全文 AES-GCM 密文落库 + HMAC 等值检索,(楼盘,门牌) DB 唯一键兜底。
 * get-or-create(exist)是客源/装修单等插件的唯一建房入口(幂等);
 * 门牌与楼盘不可改(老系统 updateHouse ignores 契约漂移教训 → 显式白名单 + 文档声明),
 * 其余字段非 null 才更新(面积允许清 0);无删除端点(房屋不可删)。
 */
@Service
public class HouseService {

    public static final int CODE_HOUSE_NOT_FOUND = 330003;
    public static final int CODE_HOUSE_EXISTS = 330004;
    public static final int CODE_INVALID_PARAM = 330017;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CryptoBox crypto;
    private final YzEventBus bus;

    public HouseService(JdbcTemplate jdbc, MessageSource messages, CryptoBox crypto, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.crypto = crypto;
        this.bus = bus;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    public record HouseFields(String built, String unit, Integer floorNum, Integer doorNum,
                              Integer buildArea, Integer freeArea, Integer innerArea, Integer totalArea,
                              String houseType) {
    }

    /** 建档(业务码拦截重复);number 为门牌全文明文,服务端加密。 */
    @Transactional
    public long create(long orgId, long villageId, String number, HouseFields f, Long createdBy) {
        require(number != null && !number.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        requireVillage(orgId, villageId);
        String hmac = crypto.hmac(number.trim());
        try {
            jdbc.update("""
                    insert into customerprofile_house
                        (org_id, village_id, number_cipher, number_hmac,
                         built, unit, floor_num, door_num,
                         build_area, free_area, inner_area, total_area, house_type, created_by)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, villageId, crypto.encrypt(number.trim()), hmac,
                    f == null ? null : f.built(), f == null ? null : f.unit(),
                    f == null ? null : f.floorNum(), f == null ? null : f.doorNum(),
                    f == null ? null : f.buildArea(), f == null ? null : f.freeArea(),
                    f == null ? null : f.innerArea(), f == null ? null : f.totalArea(),
                    f == null ? null : f.houseType(), createdBy);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            bus.publish(new HouseCreatedEvent(id, orgId, villageId), "house-created-" + id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_HOUSE_EXISTS, msg("house.exists"));
        }
    }

    /** get-or-create:同楼盘同门牌存在即返回,不存在才建(并发由唯一键兜底)。 */
    @Transactional
    public Map<String, Object> exist(long orgId, long villageId, String number, HouseFields f, Long createdBy) {
        require(number != null && !number.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        requireVillage(orgId, villageId);
        String hmac = crypto.hmac(number.trim());
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from customerprofile_house where village_id = ? and number_hmac = ? limit 1",
                villageId, hmac);
        if (!found.isEmpty()) {
            return result(((Number) found.get(0).get("id")).longValue(), false);
        }
        try {
            long id = create(orgId, villageId, number, f, createdBy);
            return result(id, true);
        } catch (BizException e) {
            if (e.getCode() != CODE_HOUSE_EXISTS) {
                throw e;
            }
            // 并发建档撞唯一键:回查返回已有档(幂等)
            List<Map<String, Object>> raced = jdbc.queryForList(
                    "select id from customerprofile_house where village_id = ? and number_hmac = ? limit 1",
                    villageId, hmac);
            if (raced.isEmpty()) {
                throw e;
            }
            return result(((Number) raced.get(0).get("id")).longValue(), false);
        }
    }

    private Map<String, Object> result(long houseId, boolean created) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("houseId", houseId);
        m.put("created", created);
        return m;
    }

    private void requireVillage(long orgId, long villageId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_village where id = ? and org_id in (0, ?)",
                Integer.class, villageId, orgId);
        if (count == null || count == 0) {
            throw new BizException(VillageService.CODE_VILLAGE_NOT_FOUND, msg("village.notfound"));
        }
    }

    /** 详情:门牌解密返回(读接口;门牌属敏感数据,脱敏策略由前端展示层决定)。 */
    public Map<String, Object> get(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select h.id, h.village_id, v.name as village_name, h.number_cipher,
                       h.built, h.unit, h.floor_num, h.door_num,
                       h.build_area, h.free_area, h.inner_area, h.total_area,
                       h.house_type, h.created_at, h.updated_at
                from customerprofile_house h
                join customerprofile_village v on v.id = h.village_id
                where h.id = ? and h.org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_HOUSE_NOT_FOUND, msg("house.notfound"));
        }
        Map<String, Object> house = rows.get(0);
        house.put("number", crypto.decrypt((String) house.remove("number_cipher")));
        return house;
    }

    /** 更新白名单字段(门牌/楼盘不可改);非 null 才更新,面积允许清 0。 */
    public void update(long orgId, long id, HouseFields f) {
        require(f != null, CODE_INVALID_PARAM, "param.invalid");
        int updated = jdbc.update("""
                update customerprofile_house
                set built = ?, unit = ?, floor_num = ?, door_num = ?,
                    build_area = ?, free_area = ?, inner_area = ?, total_area = ?, house_type = ?
                where id = ? and org_id = ?
                """, f.built(), f.unit(), f.floorNum(), f.doorNum(),
                f.buildArea(), f.freeArea(), f.innerArea(), f.totalArea(), f.houseType(), id, orgId);
        if (updated == 0) {
            require(houseExists(orgId, id), CODE_HOUSE_NOT_FOUND, "house.notfound");
        }
    }

    private boolean houseExists(long orgId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_house where id = ? and org_id = ?", Integer.class, id, orgId);
        return count != null && count > 0;
    }

    /** 分页:keyword 为门牌全文精确匹配(密文等值检索走 HMAC,不支持模糊)。 */
    public Map<String, Object> page(long orgId, Long villageId, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where h.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (villageId != null) {
            where.append(" and h.village_id = ?");
            args.add(villageId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and h.number_hmac = ?");
            args.add(crypto.hmac(keyword.trim()));
        }
        Long total = jdbc.queryForObject(
                "select count(*) from customerprofile_house h" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select h.id, h.village_id, v.name as village_name,
                       h.built, h.unit, h.floor_num, h.door_num,
                       h.build_area, h.free_area, h.inner_area, h.total_area,
                       h.house_type, h.created_at
                from customerprofile_house h
                join customerprofile_village v on v.id = h.village_id
                """ + where + " order by h.id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }
}
