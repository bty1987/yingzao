package com.yingzao.addon.supplier;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.GoodsApi;
import com.yingzao.core.web.BizException;

/**
 * 三层结算条款服务(老合同七件套「五从表」收敛:品牌级底价/点位 → 品类细化 → 商品供货价/结算价)。
 * 与「合同签署流」显式分成两个实体(修老七件套混装);V1 只落数据模型 + CRUD,签署流(电子签
 * Provider 扩展点)排期 V2+。条款录入前置=该品牌已关联(数据一致性);商品级经 GoodsApi
 * 服务端校验品牌归属(修老信任前端);金额一律厘,点位=万分比(修老 long 元原值无量纲声明)。
 */
@Service
public class SupplierTermService {

    public static final int CODE_TERM_NOT_FOUND = 340014;
    public static final int CODE_CAT_TERM_EXISTS = 340013;
    public static final int CODE_GOODS_TERM_EXISTS = 340010;
    public static final int CODE_GOODS_BRAND_MISMATCH = 340011;
    public static final int CODE_GOODS_NOT_FOUND = 340012;

    public static final int SETTLE_TYPE_FLOOR = 0;
    public static final int SETTLE_TYPE_POINT = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final SupplierService supplierService;
    private final SupplierBrandService brandService;
    private final GoodsApi goodsApi;

    public SupplierTermService(JdbcTemplate jdbc, MessageSource messages,
            SupplierService supplierService, SupplierBrandService brandService, GoodsApi goodsApi) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.supplierService = supplierService;
        this.brandService = brandService;
        this.goodsApi = goodsApi;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    public record BrandTermCmd(Integer settleType, Long settleValue, Long penalty,
                               Integer warrantyMonths, String serviceFeeJson) {
    }

    /** 品牌级条款 upsert(存在则更新;前置=品牌已关联)。 */
    @Transactional
    public void saveBrandTerm(long orgId, long supplierId, long brandId, BrandTermCmd cmd) {
        supplierService.load(orgId, supplierId);
        require(brandLinked(orgId, supplierId, brandId), SupplierBrandService.CODE_BRAND_NOT_BOUND,
                "brand.notbound");
        require(cmd.settleType() != null
                && (cmd.settleType() == SETTLE_TYPE_FLOOR || cmd.settleType() == SETTLE_TYPE_POINT),
                SupplierService.CODE_INVALID_PARAM, "param.invalid");
        require(cmd.settleValue() != null && cmd.settleValue() > 0, SupplierService.CODE_INVALID_PARAM,
                "param.invalid");
        Long penalty = cmd.penalty() != null && cmd.penalty() > 0 ? cmd.penalty() : null;
        Integer warranty = cmd.warrantyMonths() != null && cmd.warrantyMonths() > 0 ? cmd.warrantyMonths() : null;
        String fee = cmd.serviceFeeJson() == null || cmd.serviceFeeJson().isBlank() ? null
                : cmd.serviceFeeJson().trim();
        int updated = jdbc.update("""
                update supplier_term_brand set settle_type = ?, settle_value = ?, penalty = ?,
                    warranty_months = ?, service_fee_json = ?
                where org_id = ? and supplier_id = ? and brand_id = ?
                """, cmd.settleType(), cmd.settleValue(), penalty, warranty, fee, orgId, supplierId, brandId);
        if (updated == 0) {
            jdbc.update("""
                    insert into supplier_term_brand
                        (org_id, supplier_id, brand_id, settle_type, settle_value, penalty, warranty_months, service_fee_json)
                    values (?,?,?,?,?,?,?,?)
                    """, orgId, supplierId, brandId, cmd.settleType(), cmd.settleValue(), penalty, warranty, fee);
        }
    }

    /** 品类级细化(挂品牌条款行;同品类唯一)。 */
    @Transactional
    public void saveCategoryTerm(long orgId, long supplierId, long brandId, long categoryId,
            int settleType, long settleValue) {
        long termBrandId = requireTermBrand(orgId, supplierId, brandId);
        require(settleType == SETTLE_TYPE_FLOOR || settleType == SETTLE_TYPE_POINT,
                SupplierService.CODE_INVALID_PARAM, "param.invalid");
        require(settleValue > 0, SupplierService.CODE_INVALID_PARAM, "param.invalid");
        try {
            jdbc.update("""
                    insert into supplier_term_category
                        (org_id, term_brand_id, category_id, settle_type, settle_value)
                    values (?,?,?,?,?)
                    """, orgId, termBrandId, categoryId, settleType, settleValue);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_CAT_TERM_EXISTS, msg("catterm.exists"));
        }
    }

    @Transactional
    public void removeCategoryTerm(long orgId, long supplierId, long brandId, long categoryId) {
        long termBrandId = requireTermBrand(orgId, supplierId, brandId);
        jdbc.update("delete from supplier_term_category where term_brand_id = ? and category_id = ? and org_id = ?",
                termBrandId, categoryId, orgId);
    }

    /** 商品级供货价/结算价(服务端校验商品存在 + 品牌归属,修老信任前端)。 */
    @Transactional
    public void saveGoodsTerm(long orgId, long supplierId, long brandId, long goodsId,
            long price, long balance) {
        long termBrandId = requireTermBrand(orgId, supplierId, brandId);
        require(price > 0 && balance > 0, SupplierService.CODE_INVALID_PARAM, "param.invalid");
        GoodsApi.GoodsSummary goods = goodsApi.queryGoods(orgId, goodsId)
                .orElseThrow(() -> new BizException(CODE_GOODS_NOT_FOUND, msg("goods.notfound")));
        require(goods.brandId() == brandId, CODE_GOODS_BRAND_MISMATCH, "goods.brandMismatch");
        try {
            jdbc.update("""
                    insert into supplier_term_goods (org_id, term_brand_id, goods_id, price, balance)
                    values (?,?,?,?,?)
                    """, orgId, termBrandId, goodsId, price, balance);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_GOODS_TERM_EXISTS, msg("goodsterm.exists"));
        }
    }

    @Transactional
    public void removeGoodsTerm(long orgId, long supplierId, long brandId, long goodsId) {
        long termBrandId = requireTermBrand(orgId, supplierId, brandId);
        jdbc.update("delete from supplier_term_goods where term_brand_id = ? and goods_id = ? and org_id = ?",
                termBrandId, goodsId, orgId);
    }

    /** 厂商全部条款(三层一次取齐:品牌条款 + 各自品类细化 + 商品行)。 */
    public List<Map<String, Object>> terms(long orgId, long supplierId) {
        supplierService.load(orgId, supplierId);
        List<Map<String, Object>> brandTerms = jdbc.queryForList("""
                select t.id, t.brand_id, bd.name as brand_name, t.settle_type, t.settle_value,
                       t.penalty, t.warranty_months, t.service_fee_json, t.created_at, t.updated_at
                from supplier_term_brand t
                left join basedata_brand bd on bd.id = t.brand_id
                where t.org_id = ? and t.supplier_id = ?
                order by t.id
                """, orgId, supplierId);
        for (Map<String, Object> term : brandTerms) {
            long termBrandId = ((Number) term.get("id")).longValue();
            term.put("categories", jdbc.queryForList("""
                    select c.id, c.category_id, c.settle_type, c.settle_value, c.created_at
                    from supplier_term_category c where c.term_brand_id = ? order by c.id
                    """, termBrandId));
            term.put("goods", jdbc.queryForList("""
                    select g.id, g.goods_id, gd.name as goods_name, g.price, g.balance, g.created_at
                    from supplier_term_goods g
                    left join basedata_goods gd on gd.id = g.goods_id
                    where g.term_brand_id = ? order by g.id
                    """, termBrandId));
        }
        return brandTerms;
    }

    private boolean brandLinked(long orgId, long supplierId, long brandId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from supplier_brand where org_id = ? and supplier_id = ? and brand_id = ?",
                Integer.class, orgId, supplierId, brandId);
        return count != null && count > 0;
    }

    private long requireTermBrand(long orgId, long supplierId, long brandId) {
        supplierService.load(orgId, supplierId);
        require(brandLinked(orgId, supplierId, brandId), SupplierBrandService.CODE_BRAND_NOT_BOUND,
                "brand.notbound");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from supplier_term_brand where org_id = ? and supplier_id = ? and brand_id = ?",
                orgId, supplierId, brandId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_TERM_NOT_FOUND, msg("term.notfound"));
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }
}
