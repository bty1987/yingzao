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
 * 主材型号库服务:老 materialmodel 与 goods 零外键,本实现补 goods_id 可空关联(NULL=独立型号)。
 * calcType 0=底价/1=点位的点位计算口径随 addon-sales 校准,V1 只存值不做计算。
 */
@Service
public class MaterialModelService {

    public static final int CODE_MODEL_NOT_FOUND = 260017;
    public static final int CODE_MODEL_EXISTS = 260018;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    public static final int CALC_TYPE_BASE = 0;
    public static final int CALC_TYPE_POINT = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public MaterialModelService(JdbcTemplate jdbc, MessageSource messages) {
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

    public record CreateModelCmd(String name, Long goodsId, Long brandId, String unit,
            Long price, Long purchase, Integer discount, Integer calcType,
            Long baseValue, Integer pointValue) {
    }

    @Transactional
    public long create(long orgId, CreateModelCmd cmd, Long createdBy) {
        require(cmd.name() != null && !cmd.name().isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(cmd.calcType() == null || cmd.calcType() == CALC_TYPE_BASE || cmd.calcType() == CALC_TYPE_POINT,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        requireGoods(orgId, cmd.goodsId());
        try {
            // 编号按 id 生成(md5 占位防并发撞号),无 counting 读改写竞态
            jdbc.update("""
                    insert into basedata_material_model
                        (org_id, no, name, goods_id, brand_id, unit, price, purchase, discount,
                         calc_type, base_value, point_value, status, created_by)
                    values (?, concat('M', left(md5(uuid()), 8)), ?,?,?,?,?,?,?,?,?,?,0,?)
                    """, orgId, cmd.name().trim(), cmd.goodsId(), cmd.brandId(), blankToNull(cmd.unit()),
                    cmd.price() == null ? 0L : cmd.price(), cmd.purchase(), cmd.discount(),
                    cmd.calcType() == null ? CALC_TYPE_BASE : cmd.calcType(), cmd.baseValue(), cmd.pointValue(),
                    createdBy);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            jdbc.update("update basedata_material_model set no = concat('M', lpad(id, 8, '0')) where id = ?", id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_MODEL_EXISTS, msg("model.exists"));
        }
    }

    /** 编辑=全量回传覆盖(name 必填,表单单次提交);no/org/status 不在白名单。 */
    @Transactional
    public void update(long orgId, long id, String name, Long goodsId, Long brandId, String unit,
            Long price, Long purchase, Integer discount, Integer calcType, Long baseValue, Integer pointValue) {
        load(orgId, id);
        require(name != null && !name.isBlank(), CategoryService.CODE_INVALID_PARAM, "param.invalid");
        require(calcType == null || calcType == CALC_TYPE_BASE || calcType == CALC_TYPE_POINT,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        requireGoods(orgId, goodsId);
        try {
            jdbc.update("""
                    update basedata_material_model set name = ?, goods_id = ?, brand_id = ?, unit = ?,
                        price = ?, purchase = ?, discount = ?, calc_type = ?, base_value = ?, point_value = ?
                    where id = ? and org_id = ?
                    """, name.trim(), goodsId, brandId, blankToNull(unit), price, purchase,
                    discount, calcType, baseValue, pointValue, id, orgId);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_MODEL_EXISTS, msg("model.exists"));
        }
    }

    @Transactional
    public void updateStatus(long orgId, long id, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED,
                CategoryService.CODE_INVALID_PARAM, "param.invalid");
        load(orgId, id);
        jdbc.update("update basedata_material_model set status = ? where id = ? and org_id = ?", status, id, orgId);
    }

    public Map<String, Object> page(long orgId, String keyword, Long brandId, Integer status,
            int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where m.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (m.name like concat('%', ?, '%') or m.no like concat('%', ?, '%'))");
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        if (brandId != null) {
            where.append(" and m.brand_id = ?");
            args.add(brandId);
        }
        if (status != null) {
            where.append(" and m.status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from basedata_material_model m" + where, Long.class,
                args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id, m.no, m.name, m.goods_id, g.name as goods_name, m.brand_id, b.name as brand_name,
                       m.unit, m.price, m.purchase, m.discount, m.calc_type, m.base_value, m.point_value,
                       m.status, m.created_at
                from basedata_material_model m
                left join basedata_goods g on g.id = m.goods_id
                left join basedata_brand b on b.id = m.brand_id
                """ + where + " order by m.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private void load(long orgId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from basedata_material_model where id = ? and org_id = ?", Integer.class, id, orgId);
        if (count == null || count == 0) {
            throw new BizException(CODE_MODEL_NOT_FOUND, msg("model.notfound"));
        }
    }

    private void requireGoods(long orgId, Long goodsId) {
        if (goodsId == null) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from basedata_goods where id = ? and org_id = ?", Integer.class, goodsId, orgId);
        if (count == null || count == 0) {
            throw new BizException(GoodsService.CODE_GOODS_NOT_FOUND, msg("goods.notfound"));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
