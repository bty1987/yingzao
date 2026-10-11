package com.yingzao.addon.market;

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
 * 官网案例库五件套(城市/人物/案例/图片/报名):对客展示 CMS,上下架=布尔开关非审核流
 * (行业共性保留)。报名 = 一种进线入口(复用进线链路:幂等+手机密文+归因,废老独立明文
 * ecustomer 表);案例删除级联删图片行(修老孤儿文件记录),报名进线以 case_name 快照
 * 保留历史可读。每城每类露 10 条的硬编码 C 端口径不迁(条数归官网端 V2,后台不限)。
 */
@Service
public class ShowcaseService {

    public static final int CODE_CITY_NOT_FOUND = 280016;
    public static final int CODE_CITY_NAME_DUP = 280017;
    public static final int CODE_PERSON_NOT_FOUND = 280018;
    public static final int CODE_PERSON_NAME_DUP = 280019;
    public static final int CODE_CASE_NOT_FOUND = 280020;
    public static final int CODE_CASE_NOT_REMOVABLE = 280021;
    public static final int CODE_PICTURE_NOT_FOUND = 280022;
    public static final int CODE_LEAD_PHONE_REQUIRED = 280023;
    public static final int CODE_CITY_IN_USE = 280025;
    public static final int CODE_PERSON_IN_USE = 280026;

    public static final int TYPE_RENDER = 0;
    public static final int TYPE_CONSTRUCTION = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final InboundService inbounds;

    public ShowcaseService(JdbcTemplate jdbc, MessageSource messages, InboundService inbounds) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.inbounds = inbounds;
    }

    // ---- 城市字典 ----

    @Transactional
    public long createCity(long orgId, String name, String pinyin) {
        requireCityNameFree(orgId, name, 0);
        jdbc.update("insert into market_ecity(org_id, name, pinyin, status) values (?,?,?,0)",
                orgId, name, pinyin);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateCity(long orgId, long id, String name, String pinyin, Integer status) {
        requireCityExists(orgId, id);
        requireCityNameFree(orgId, name, id);
        jdbc.update("update market_ecity set name = ?, pinyin = ?, status = ? where id = ? and org_id = ?",
                name, pinyin, status == null ? 0 : status, id, orgId);
    }

    @Transactional
    public void deleteCity(long orgId, long id) {
        requireCityExists(orgId, id);
        Integer used = jdbc.queryForObject(
                "select count(*) from market_ecase where city_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw new BizException(CODE_CITY_IN_USE,
                    messages.getMessage("market.city.inUse", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("delete from market_ecity where id = ? and org_id = ?", id, orgId);
    }

    public List<Map<String, Object>> listCities(long orgId, Integer status) {
        if (status == null) {
            return jdbc.queryForList(
                    "select id, name, pinyin, status, created_at from market_ecity where org_id = ? order by id",
                    orgId);
        }
        return jdbc.queryForList(
                "select id, name, pinyin, status, created_at from market_ecity where org_id = ? and status = ? order by id",
                orgId, status);
    }

    // ---- 人物库 ----

    @Transactional
    public long createPerson(long orgId, String name, String pinyin, int type, String title) {
        requirePersonNameFree(orgId, name, type, 0);
        jdbc.update("insert into market_eperson(org_id, name, pinyin, type, title, status) values (?,?,?,?,?,0)",
                orgId, name, pinyin, type, title);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updatePerson(long orgId, long id, String name, String pinyin, Integer type,
            String title, Integer status) {
        requirePersonExists(orgId, id);
        int personType = type == null ? 0 : type;
        requirePersonNameFree(orgId, name, personType, id);
        jdbc.update(
                "update market_eperson set name = ?, pinyin = ?, type = ?, title = ?, status = ? where id = ? and org_id = ?",
                name, pinyin, personType, title, status == null ? 0 : status, id, orgId);
    }

    @Transactional
    public void deletePerson(long orgId, long id) {
        requirePersonExists(orgId, id);
        Integer used = jdbc.queryForObject(
                "select count(*) from market_ecase where designer_id = ? or supervisor_id = ?",
                Integer.class, id, id);
        if (used != null && used > 0) {
            throw new BizException(CODE_PERSON_IN_USE,
                    messages.getMessage("market.person.inUse", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("delete from market_eperson where id = ? and org_id = ?", id, orgId);
    }

    public List<Map<String, Object>> listPersons(long orgId, Integer type, Integer status) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        return jdbc.queryForList(
                "select id, name, pinyin, type, title, status, created_at from market_eperson" + where + " order by id",
                args.toArray());
    }

    // ---- 案例 ----

    public record PictureItem(String url, Integer sort) {
    }

    public record CaseCmd(Long cityId, Long designerId, Long supervisorId, String name, String village,
            String style, String layout, Long area, Integer cycle, String coverUrl, Integer type,
            List<PictureItem> pictures) {
    }

    @Transactional
    public long createCase(long orgId, CaseCmd cmd) {
        requireCityExists(orgId, cmd.cityId());
        if (cmd.designerId() != null) {
            requirePersonExists(orgId, cmd.designerId());
        }
        if (cmd.supervisorId() != null) {
            requirePersonExists(orgId, cmd.supervisorId());
        }
        int type = cmd.type() == null ? TYPE_RENDER : cmd.type();
        // 工期仅施工图有,效果图恒 0(老口径:效果图锁 0)
        Integer cycle = type == TYPE_CONSTRUCTION && cmd.cycle() != null ? cmd.cycle() : 0;
        jdbc.update("""
                insert into market_ecase(org_id, city_id, designer_id, supervisor_id, name, village,
                    style, layout, area, cycle, cover_url, type, status)
                values (?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, orgId, cmd.cityId(), cmd.designerId(), cmd.supervisorId(), cmd.name(), cmd.village(),
                cmd.style(), cmd.layout(), cmd.area(), cycle, cmd.coverUrl(), type);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        savePictures(id, cmd.pictures());
        return id;
    }

    @Transactional
    public void updateCase(long orgId, long id, CaseCmd cmd) {
        requireCaseExists(orgId, id);
        requireCityExists(orgId, cmd.cityId());
        if (cmd.designerId() != null) {
            requirePersonExists(orgId, cmd.designerId());
        }
        if (cmd.supervisorId() != null) {
            requirePersonExists(orgId, cmd.supervisorId());
        }
        int type = cmd.type() == null ? TYPE_RENDER : cmd.type();
        Integer cycle = type == TYPE_CONSTRUCTION && cmd.cycle() != null ? cmd.cycle() : 0;
        jdbc.update("""
                update market_ecase set city_id = ?, designer_id = ?, supervisor_id = ?, name = ?,
                    village = ?, style = ?, layout = ?, area = ?, cycle = ?, cover_url = ?, type = ?
                where id = ? and org_id = ?
                """, cmd.cityId(), cmd.designerId(), cmd.supervisorId(), cmd.name(), cmd.village(),
                cmd.style(), cmd.layout(), cmd.area(), cycle, cmd.coverUrl(), type, id, orgId);
        if (cmd.pictures() != null) {
            // 全量替换语义:提交了 pictures 即重建图片列表
            jdbc.update("delete from market_epicture where case_id = ?", id);
            savePictures(id, cmd.pictures());
        }
    }

    /** 上下架翻转(布尔开关非审核流)。 */
    @Transactional
    public void changeCaseStatus(long orgId, long id, int status) {
        int updated = jdbc.update(
                "update market_ecase set status = ? where id = ? and org_id = ?", status, id, orgId);
        if (updated == 0) {
            requireCaseExists(orgId, id);
        }
    }

    /** 删除:仅下架态可删(上架先下架);级联删图片行(修老孤儿文件记录)。 */
    @Transactional
    public void deleteCase(long orgId, long id) {
        Map<String, Object> row = requireCaseExists(orgId, id);
        if (((Number) row.get("status")).intValue() == 1) {
            throw new BizException(CODE_CASE_NOT_REMOVABLE,
                    messages.getMessage("market.case.notRemovable", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("delete from market_epicture where case_id = ?", id);
        jdbc.update("delete from market_ecase where id = ? and org_id = ?", id, orgId);
    }

    public Map<String, Object> caseDetail(long orgId, long id) {
        requireCaseExists(orgId, id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id, e.org_id, e.city_id, ct.name as city_name, e.designer_id,
                       dp.name as designer_name, e.supervisor_id, sp.name as supervisor_name,
                       e.name, e.village, e.style, e.layout, e.area, e.cycle, e.cover_url,
                       e.type, e.status, e.created_at
                from market_ecase e
                left join market_ecity ct on ct.id = e.city_id
                left join market_eperson dp on dp.id = e.designer_id
                left join market_eperson sp on sp.id = e.supervisor_id
                where e.id = ? and e.org_id = ? limit 1
                """, id, orgId);
        Map<String, Object> row = rows.get(0);
        row.put("pictures", jdbc.queryForList(
                "select id, url, sort from market_epicture where case_id = ? order by sort, id", id));
        return row;
    }

    /** 后台筛选白名单=类型/状态/城市/设计师/监理/风格 like/楼盘 like(老口径收编,去 min-max 字符串区间)。 */
    public Map<String, Object> pageCases(long orgId, Long cityId, Integer type, Integer status,
            Long designerId, Long supervisorId, String style, String name, int pageNo, int size) {
        StringBuilder where = new StringBuilder(" where e.org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (cityId != null) {
            where.append(" and e.city_id = ?");
            args.add(cityId);
        }
        if (type != null) {
            where.append(" and e.type = ?");
            args.add(type);
        }
        if (status != null) {
            where.append(" and e.status = ?");
            args.add(status);
        }
        if (designerId != null) {
            where.append(" and e.designer_id = ?");
            args.add(designerId);
        }
        if (supervisorId != null) {
            where.append(" and e.supervisor_id = ?");
            args.add(supervisorId);
        }
        if (style != null && !style.isBlank()) {
            where.append(" and e.style like concat('%', ?, '%')");
            args.add(style.trim());
        }
        if (name != null && !name.isBlank()) {
            where.append(" and e.name like concat('%', ?, '%')");
            args.add(name.trim());
        }
        Long total = jdbc.queryForObject(
                "select count(*) from market_ecase e" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(size);
        allArgs.add((pageNo - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id, e.city_id, ct.name as city_name, e.designer_id, dp.name as designer_name,
                       e.supervisor_id, sp.name as supervisor_name, e.name, e.village, e.style, e.layout,
                       e.area, e.cycle, e.cover_url, e.type, e.status, e.created_at,
                       (select count(*) from market_epicture p where p.case_id = e.id) as picture_count
                from market_ecase e
                left join market_ecity ct on ct.id = e.city_id
                left join market_eperson dp on dp.id = e.designer_id
                left join market_eperson sp on sp.id = e.supervisor_id
                """ + where + " order by e.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 图片 ----

    @Transactional
    public void addPicture(long orgId, long caseId, String url, Integer sort) {
        requireCaseExists(orgId, caseId);
        if (url == null || url.isBlank()) {
            return;
        }
        jdbc.update("insert into market_epicture(case_id, url, sort) values (?,?,?)",
                caseId, url, sort == null ? 0 : sort);
    }

    @Transactional
    public void deletePicture(long orgId, long id) {
        int deleted = jdbc.update("delete from market_epicture where id = ?", id);
        if (deleted == 0) {
            throw new BizException(CODE_PICTURE_NOT_FOUND,
                    messages.getMessage("market.picture.notfound", null, LocaleContextHolder.getLocale()));
        }
    }

    // ---- 报名(一种进线入口,复用进线链路) ----

    /** 报名留资:手机必填(无法跟进的留资无价值),承接固定 chance 档,进线三键空+case 快照。 */
    @Transactional
    public Map<String, Object> leadForCase(long orgId, long operatorId, long caseId, String name,
            String phone, String note, List<InboundService.FieldItem> items) {
        Map<String, Object> row = requireCaseExists(orgId, caseId);
        if (phone == null || phone.isBlank()) {
            throw new BizException(CODE_LEAD_PHONE_REQUIRED,
                    messages.getMessage("market.lead.phoneRequired", null, LocaleContextHolder.getLocale()));
        }
        InboundService.CreateCmd cmd = new InboundService.CreateCmd(null, null, null, caseId,
                (String) row.get("name"), name, phone, com.yingzao.addons.api.event.InboundAcceptedEvent.MODE_CHANCE,
                note, null, items);
        return inbounds.create(orgId, operatorId, cmd);
    }

    /** 报名进线列表(按案例)。 */
    public List<Map<String, Object>> listLeads(long orgId, Long caseId, int limit) {
        StringBuilder where = new StringBuilder(" where i.org_id = ? and i.case_id is not null");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (caseId != null) {
            where.append(" and i.case_id = ?");
            args.add(caseId);
        }
        args.add(Math.min(Math.max(limit, 1), 200));
        return jdbc.queryForList("""
                select i.id, i.inbound_no, i.case_id, i.case_name, i.name, i.accept_mode, i.status,
                       i.error_msg, i.customer_lead_id, i.chance_id, i.created_at
                from market_inbound i
                """ + where + " order by i.id desc limit ?", args.toArray());
    }

    // ---- 内部 ----

    private Map<String, Object> requireCityExists(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name from market_ecity where id = ? and org_id = ? limit 1", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CITY_NOT_FOUND,
                    messages.getMessage("market.city.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private void requireCityNameFree(long orgId, String name, long selfId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from market_ecity where org_id = ? and name = ? and id != ?",
                Integer.class, orgId, name, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_CITY_NAME_DUP,
                    messages.getMessage("market.city.nameDup", null, LocaleContextHolder.getLocale()));
        }
    }

    private Map<String, Object> requirePersonExists(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name from market_eperson where id = ? and org_id = ? limit 1", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_PERSON_NOT_FOUND,
                    messages.getMessage("market.person.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private void requirePersonNameFree(long orgId, String name, int type, long selfId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from market_eperson where org_id = ? and type = ? and name = ? and id != ?",
                Integer.class, orgId, type, name, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_PERSON_NAME_DUP,
                    messages.getMessage("market.person.nameDup", null, LocaleContextHolder.getLocale()));
        }
    }

    private Map<String, Object> requireCaseExists(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id, e.name, e.status from market_ecase e where e.id = ? and e.org_id = ? limit 1
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CASE_NOT_FOUND,
                    messages.getMessage("market.case.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private void savePictures(long caseId, List<PictureItem> pictures) {
        if (pictures == null) {
            return;
        }
        for (PictureItem picture : pictures) {
            if (picture.url() == null || picture.url().isBlank()) {
                continue;
            }
            jdbc.update("insert into market_epicture(case_id, url, sort) values (?,?,?)",
                    caseId, picture.url(), picture.sort() == null ? 0 : picture.sort());
        }
    }
}
