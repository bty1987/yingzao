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

import com.yingzao.addons.api.event.GoodsCreatedEvent;
import com.yingzao.addons.api.event.GoodsPriceChangedEvent;
import com.yingzao.addons.api.event.GoodsStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 商品主档服务(含 SKU 属性/多价格体系/套餐成员三个子资源)。
 * 对老缺陷的显式修正:no/barcode 查重下沉 DB 唯一键(老系统全程无查重)、
 * update 显式白名单(no/org/type/status 不可被 VO 覆写,老 updateGoods 信任前端)、
 * 启停走显式端点并发领域事件(老 updateGoodsForStatus 直落 VO 值无校验无留痕)、
 * 套餐成员不可再为套餐 + 软删替代物理删;价格变更发布 GoodsPriceChangedEvent。
 */
@Service
public class GoodsService {

    public static final int CODE_GOODS_NOT_FOUND = 260007;
    public static final int CODE_GOODS_NO_EXISTS = 260008;
    public static final int CODE_GOODS_BARCODE_EXISTS = 260009;
    public static final int CODE_GOODS_NAME_EXISTS = 260010;
    public static final int CODE_SKU_ATTR_EXISTS = 260011;
    public static final int CODE_PRICE_EXISTS = 260012;
    public static final int CODE_PRICE_NOT_FOUND = 260013;
    public static final int CODE_MEMBER_IS_PACKAGE = 260014;
    public static final int CODE_MEMBER_EXISTS = 260015;
    public static final int CODE_MEMBER_NOT_FOUND = 260016;
    public static final int CODE_SKU_ATTR_NOT_FOUND = 260021;
    public static final int CODE_GOODS_NOT_PACKAGE = 260022;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    public static final int TYPE_GOODS = 0;
    public static final int TYPE_PACKAGE = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;

    public GoodsService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus) {
        this.jdbc = jdbc;
        this.messages = messages;
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

    // ---- 主档 ----

    public record CreateGoodsCmd(String name, String alias, String pinyin, String barcode,
            long categoryId, Long brandId, String unit, int type,
            Long purchase, Long selling, Long confine, Integer unitRate) {
    }

    @Transactional
    public long create(long orgId, CreateGoodsCmd cmd, Long createdBy) {
        require(cmd.name() != null && !cmd.name().isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(cmd.unit() != null && !cmd.unit().isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(cmd.type() == TYPE_GOODS || cmd.type() == TYPE_PACKAGE, CategoryService.CODE_INVALID_PARAM,
                "param.invalid");
        require(cmd.selling() == null || cmd.selling() >= 0, CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(cmd.purchase() == null || cmd.purchase() >= 0, CategoryService.CODE_INVALID_PARAM, "param.invalid");
        requireCategory(orgId, cmd.categoryId());
        try {
            jdbc.update("""
                    insert into basedata_goods
                        (org_id, no, name, alias, pinyin, barcode, category_id, brand_id, unit, type,
                         purchase, selling, confine, unit_rate, status, created_by)
                    values (?, concat('G', left(md5(uuid()), 8)), ?,?,?,?,?,?,?,?,?,?,?,?,0,?)
                    """, orgId, cmd.name().trim(), blankToNull(cmd.alias()), blankToNull(cmd.pinyin()),
                    blankToNull(cmd.barcode()), cmd.categoryId(), cmd.brandId(), cmd.unit().trim(), cmd.type(),
                    cmd.purchase() == null ? 0L : cmd.purchase(), cmd.selling() == null ? 0L : cmd.selling(),
                    cmd.confine(), cmd.unitRate() == null ? 1000 : cmd.unitRate(), createdBy);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            jdbc.update("update basedata_goods set no = concat('G', lpad(id, 8, '0')) where id = ?", id);
            bus.publish(new GoodsCreatedEvent(id, orgId, "G" + String.format("%08d", id),
                    cmd.name().trim(), cmd.categoryId(), cmd.brandId()), "goods-created-" + id);
            return id;
        } catch (DuplicateKeyException e) {
            throw resolveDuplicate(orgId, cmd);
        }
    }

    /** 唯一键冲突定位:no 占位并发兜底 / name / barcode,给出准确错误码。 */
    private BizException resolveDuplicate(long orgId, CreateGoodsCmd cmd) {
        if (cmd.barcode() != null && !cmd.barcode().isBlank() && countBy(orgId, "barcode", cmd.barcode().trim()) > 0) {
            return new BizException(CODE_GOODS_BARCODE_EXISTS, msg("goods.barcodeExists"));
        }
        if (countBy(orgId, "name", cmd.name().trim()) > 0) {
            return new BizException(CODE_GOODS_NAME_EXISTS, msg("goods.nameExists"));
        }
        return new BizException(CODE_GOODS_NO_EXISTS, msg("goods.noExists"));
    }

    private long countBy(long orgId, String column, String value) {
        // column 来自内部白名单调用点,值参数化
        Long count = jdbc.queryForObject("select count(*) from basedata_goods where org_id = ? and " + column
                + " = ?", Long.class, orgId, value);
        return count == null ? 0 : count;
    }

    /** 更新显式白名单:no/org/type/status 不在列(老 updateGoods 信任前端覆写的反面教材)。 */
    @Transactional
    public void update(long orgId, long id, String name, String alias, String pinyin, String barcode,
            Long categoryId, Long brandId, String unit, Long purchase, Long selling, Long confine,
            Integer unitRate) {
        Map<String, Object> goods = load(orgId, id);
        if (categoryId != null) {
            requireCategory(orgId, categoryId);
        }
        try {
            jdbc.update("""
                    update basedata_goods set name = ?, alias = ?, pinyin = ?, barcode = ?, category_id = ?,
                        brand_id = ?, unit = ?, purchase = ?, selling = ?, confine = ?, unit_rate = ?
                    where id = ? and org_id = ?
                    """,
                    firstNonBlank(name, goods.get("name")), firstNonNull(alias, goods.get("alias")),
                    firstNonNull(pinyin, goods.get("pinyin")), firstNonNull(barcode, goods.get("barcode")),
                    categoryId != null ? categoryId : goods.get("category_id"),
                    brandId != null ? brandId : goods.get("brand_id"),
                    firstNonBlank(unit, goods.get("unit")),
                    purchase != null ? purchase : goods.get("purchase"),
                    selling != null ? selling : goods.get("selling"),
                    confine != null ? confine : goods.get("confine"),
                    unitRate != null ? unitRate : goods.get("unit_rate"),
                    id, orgId);
        } catch (DuplicateKeyException e) {
            throw resolveDuplicate(orgId, new CreateGoodsCmd(name, alias, pinyin, barcode,
                    categoryId == null ? 0 : categoryId, brandId, unit, TYPE_GOODS, purchase, selling, confine,
                    unitRate));
        }
        bus.publish(new GoodsPriceChangedEvent(id, orgId,
                selling != null ? selling : asLong(goods.get("selling")),
                confine != null ? confine : asLong(goods.get("confine")),
                unitRate != null ? unitRate : asInt(goods.get("unit_rate"))), "goods-price-changed-" + id);
    }

    @Transactional
    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, id);
        jdbc.update("update basedata_goods set status = ? where id = ? and org_id = ?", status, id, orgId);
        bus.publish(new GoodsStatusChangedEvent(id, orgId, status), "goods-status-changed-" + id);
    }

    public Map<String, Object> page(long orgId, String keyword, Long categoryId, Long brandId,
            Integer status, Integer type, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where g.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (g.name like concat('%', ?, '%') or g.no like concat('%', ?, '%')")
                    .append(" or g.pinyin like concat('%', ?, '%') or g.barcode = ?)");
            args.add(keyword.trim());
            args.add(keyword.trim());
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        if (categoryId != null) {
            where.append(" and g.category_id = ?");
            args.add(categoryId);
        }
        if (brandId != null) {
            where.append(" and g.brand_id = ?");
            args.add(brandId);
        }
        if (status != null) {
            where.append(" and g.status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" and g.type = ?");
            args.add(type);
        }
        Long total = jdbc.queryForObject("select count(*) from basedata_goods g" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select g.id, g.no, g.name, g.alias, g.barcode, g.category_id, c.name as category_name,
                       g.brand_id, b.name as brand_name, g.unit, g.type, g.purchase, g.selling, g.confine,
                       g.unit_rate, g.status, g.created_at
                from basedata_goods g
                left join basedata_category c on c.id = g.category_id
                left join basedata_brand b on b.id = g.brand_id
                """ + where + " order by g.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> get(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select g.id, g.no, g.name, g.alias, g.pinyin, g.barcode, g.category_id,
                       c.name as category_name, g.brand_id, b.name as brand_name, g.unit, g.type,
                       g.purchase, g.selling, g.confine, g.unit_rate, g.status,
                       g.created_by, g.created_at, g.updated_at
                from basedata_goods g
                left join basedata_category c on c.id = g.category_id
                left join basedata_brand b on b.id = g.brand_id
                where g.id = ? and g.org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_GOODS_NOT_FOUND, msg("goods.notfound"));
        }
        return rows.get(0);
    }

    // ---- SKU 属性(颜色/规格同构;统一查重,修老 addFormat 无查重) ----

    public List<Map<String, Object>> skuAttrs(long orgId, long goodsId, boolean includeDisabled) {
        load(orgId, goodsId);
        StringBuilder where = new StringBuilder(" where goods_id = ?");
        if (!includeDisabled) {
            where.append(" and status = ").append(STATUS_ENABLED);
        }
        return jdbc.queryForList("select id, attr_type, value, status from basedata_goods_sku_attr" + where
                + " order by attr_type, id", goodsId);
    }

    @Transactional
    public long addSkuAttr(long orgId, long goodsId, int attrType, String value) {
        require(attrType == 0 || attrType == 1, CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(value != null && !value.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, goodsId);
        try {
            jdbc.update("insert into basedata_goods_sku_attr (org_id, goods_id, attr_type, value) values (?,?,?,?)",
                    orgId, goodsId, attrType, value.trim());
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_SKU_ATTR_EXISTS, msg("skuattr.exists"));
        }
        return jdbc.queryForObject("""
                select id from basedata_goods_sku_attr where goods_id = ? and attr_type = ? and value = ?
                """, Long.class, goodsId, attrType, value.trim());
    }

    @Transactional
    public void updateSkuAttrStatus(long orgId, long goodsId, long attrId, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, goodsId);
        int updated = jdbc.update("""
                update basedata_goods_sku_attr set status = ? where id = ? and goods_id = ? and org_id = ?
                """, status, attrId, goodsId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_SKU_ATTR_NOT_FOUND, msg("skuattr.notfound"));
        }
    }

    // ---- 多价格体系(补时间有效性 + 查重,老 add 无查重) ----

    public List<Map<String, Object>> prices(long orgId, long goodsId) {
        load(orgId, goodsId);
        return jdbc.queryForList("""
                select id, name, purchase, selling, confine, ctype, cvalue, begin_time, end_time, status
                from basedata_goods_price where goods_id = ? and org_id = ? order by id
                """, goodsId, orgId);
    }

    public record PriceCmd(String name, Long purchase, Long selling, Long confine,
            Integer ctype, Long cvalue, String beginTime, String endTime) {
    }

    @Transactional
    public long addPrice(long orgId, long goodsId, PriceCmd cmd) {
        require(cmd.name() != null && !cmd.name().isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, goodsId);
        try {
            jdbc.update("""
                    insert into basedata_goods_price
                        (org_id, goods_id, name, purchase, selling, confine, ctype, cvalue, begin_time, end_time)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """, orgId, goodsId, cmd.name().trim(), cmd.purchase(), cmd.selling(), cmd.confine(),
                    cmd.ctype(), cmd.cvalue(), parseDate(cmd.beginTime()), parseDate(cmd.endTime()));
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PRICE_EXISTS, msg("price.exists"));
        }
        return jdbc.queryForObject("select id from basedata_goods_price where goods_id = ? and name = ?",
                Long.class, goodsId, cmd.name().trim());
    }

    @Transactional
    public void updatePrice(long orgId, long goodsId, long priceId, PriceCmd cmd) {
        load(orgId, goodsId);
        try {
            int updated = jdbc.update("""
                    update basedata_goods_price set name = ?, purchase = ?, selling = ?, confine = ?,
                        ctype = ?, cvalue = ?, begin_time = ?, end_time = ?
                    where id = ? and goods_id = ? and org_id = ?
                    """, cmd.name() == null ? null : cmd.name().trim(), cmd.purchase(), cmd.selling(),
                    cmd.confine(), cmd.ctype(), cmd.cvalue(), parseDate(cmd.beginTime()), parseDate(cmd.endTime()),
                    priceId, goodsId, orgId);
            if (updated == 0) {
                throw new BizException(CODE_PRICE_NOT_FOUND, msg("price.notfound"));
            }
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PRICE_EXISTS, msg("price.exists"));
        }
    }

    @Transactional
    public void updatePriceStatus(long orgId, long goodsId, long priceId, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        int updated = jdbc.update(
                "update basedata_goods_price set status = ? where id = ? and goods_id = ? and org_id = ?",
                status, priceId, goodsId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_PRICE_NOT_FOUND, msg("price.notfound"));
        }
    }

    // ---- 套餐成员(成员不可再为套餐;软删替代老物理删) ----

    public List<Map<String, Object>> packageMembers(long orgId, long packageGoodsId, boolean includeDisabled) {
        load(orgId, packageGoodsId);
        StringBuilder where = new StringBuilder(
                " where p.package_goods_id = ? and p.org_id = ?");
        if (!includeDisabled) {
            where.append(" and p.status = ").append(STATUS_ENABLED);
        }
        return jdbc.queryForList("""
                select p.id, p.package_goods_id, p.member_goods_id, g.name as member_name, g.no as member_no,
                       g.unit as member_unit, p.num, p.status
                from basedata_goods_package p
                left join basedata_goods g on g.id = p.member_goods_id
                """ + where + " order by p.id", packageGoodsId, orgId);
    }

    @Transactional
    public long addPackageMember(long orgId, long packageGoodsId, long memberGoodsId, int num) {
        require(num > 0, CategoryService.CODE_INVALID_PARAM, "param.invalid");
        Map<String, Object> pkg = load(orgId, packageGoodsId);
        require(asInt(pkg.get("type")) == TYPE_PACKAGE, CODE_GOODS_NOT_PACKAGE, "goods.notPackage");
        Map<String, Object> member = load(orgId, memberGoodsId);
        require(asInt(member.get("type")) == TYPE_GOODS, CODE_MEMBER_IS_PACKAGE, "package.memberIsPackage");
        try {
            jdbc.update("""
                    insert into basedata_goods_package (org_id, package_goods_id, member_goods_id, num)
                    values (?,?,?,?)
                    """, orgId, packageGoodsId, memberGoodsId, num);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_MEMBER_EXISTS, msg("package.memberExists"));
        }
        return jdbc.queryForObject("""
                select id from basedata_goods_package
                where package_goods_id = ? and member_goods_id = ?
                """, Long.class, packageGoodsId, memberGoodsId);
    }

    @Transactional
    public void updatePackageMemberStatus(long orgId, long packageGoodsId, long memberId, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        int updated = jdbc.update("""
                update basedata_goods_package set status = ?
                where id = ? and package_goods_id = ? and org_id = ?
                """, status, memberId, packageGoodsId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_MEMBER_NOT_FOUND, msg("package.memberNotFound"));
        }
    }

    // ---- 内部 ----

    private Map<String, Object> load(long orgId, long id) {
        // update 白名单回退需要全量现值,缺列会把 NOT NULL 列置 null
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, no, name, alias, pinyin, barcode, category_id, brand_id, unit,
                       type, purchase, selling, confine, unit_rate, status
                from basedata_goods where id = ? and org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_GOODS_NOT_FOUND, msg("goods.notfound"));
        }
        return rows.get(0);
    }

    private void requireCategory(long orgId, long categoryId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from basedata_category where id = ? and status = ? and org_id in (?, ?)
                """, Integer.class, categoryId, CategoryService.STATUS_ENABLED,
                CategoryService.ORG_PLATFORM, orgId);
        if (count == null || count == 0) {
            throw new BizException(CategoryService.CODE_CATEGORY_NOT_FOUND, msg("category.notfound"));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Object firstNonBlank(String value, Object fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private Object firstNonNull(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    private java.sql.Date parseDate(String value) {
        return value == null || value.isBlank() ? null : java.sql.Date.valueOf(value.trim());
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Integer asInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
