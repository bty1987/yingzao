package com.yingzao.addon.supplier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.BrandApi;
import com.yingzao.addons.api.event.SupplierBrandChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 厂商品牌关联服务:多对多 + is_primary 直属位(修老 brand.ocuid 单列与关联表双轨并存)。
 * 纪律:关联写点校验厂商【存在且启用】(停用拒新关联,存量不动,修老"任何 uid 都能当厂商")+
 * 品牌存在(BrandApi 契约,零反向依赖);直属位每品牌唯一,切换语义(新直属顶旧直属,
 * 事务内先清后置,同 customerprofile 默认号码先例);解绑不删历史(结算/选材快照自持)。
 */
@Service
public class SupplierBrandService {

    public static final int CODE_BRAND_NOT_FOUND = 340009;
    public static final int CODE_BRAND_BOUND = 340007;
    public static final int CODE_BRAND_NOT_BOUND = 340008;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;
    private final SupplierService supplierService;
    private final BrandApi brandApi;

    public SupplierBrandService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus,
            SupplierService supplierService, BrandApi brandApi) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
        this.supplierService = supplierService;
        this.brandApi = brandApi;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    /** 关联品牌(isPrimary=true 直属;停用厂商拒新关联)。 */
    @Transactional
    public void bind(long orgId, long supplierId, long brandId, boolean isPrimary) {
        supplierService.loadActive(orgId, supplierId);
        require(brandApi.queryBrand(brandId).isPresent(), CODE_BRAND_NOT_FOUND, "brand.notfound");
        try {
            jdbc.update("""
                    insert into supplier_brand (org_id, supplier_id, brand_id, is_primary)
                    values (?,?,?,?)
                    """, orgId, supplierId, brandId, isPrimary ? 1 : 0);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_BRAND_BOUND, msg("brand.bound"));
        }
        if (isPrimary) {
            promotePrimary(orgId, supplierId, brandId);
        }
        bus.publish(new SupplierBrandChangedEvent(supplierId, orgId, brandId, "bound"),
                "supplier-brand-" + supplierId + "-" + brandId + "-bound");
    }

    /** 解绑(不删历史:结算/选材快照自持;连带摘除直属位)。 */
    @Transactional
    public void unbind(long orgId, long supplierId, long brandId) {
        int removed = jdbc.update(
                "delete from supplier_brand where org_id = ? and supplier_id = ? and brand_id = ?",
                orgId, supplierId, brandId);
        require(removed > 0, CODE_BRAND_NOT_BOUND, "brand.notbound");
        bus.publish(new SupplierBrandChangedEvent(supplierId, orgId, brandId, "unbound"),
                "supplier-brand-" + supplierId + "-" + brandId + "-unbound");
    }

    /** 设直属(切换语义:同品牌旧直属被顶替;修老 ocuid 与关联表双轨语义漂移)。 */
    @Transactional
    public void setPrimary(long orgId, long supplierId, long brandId) {
        require(loadRow(orgId, supplierId, brandId), CODE_BRAND_NOT_BOUND, "brand.notbound");
        promotePrimary(orgId, supplierId, brandId);
        bus.publish(new SupplierBrandChangedEvent(supplierId, orgId, brandId, "primary"),
                "supplier-brand-" + supplierId + "-" + brandId + "-primary");
    }

    private void promotePrimary(long orgId, long supplierId, long brandId) {
        jdbc.update("update supplier_brand set is_primary = 0 where org_id = ? and brand_id = ? and is_primary = 1",
                orgId, brandId);
        jdbc.update(
                "update supplier_brand set is_primary = 1 where org_id = ? and supplier_id = ? and brand_id = ?",
                orgId, supplierId, brandId);
    }

    private boolean loadRow(long orgId, long supplierId, long brandId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from supplier_brand where org_id = ? and supplier_id = ? and brand_id = ?",
                Integer.class, orgId, supplierId, brandId);
        return count != null && count > 0;
    }

    /** 关联清单(携品牌名快照;品牌是全局公共字典,名称经 join 只读展示)。 */
    public List<Map<String, Object>> brands(long orgId, long supplierId) {
        return jdbc.queryForList("""
                select b.id, b.brand_id, bd.name as brand_name, b.is_primary, b.created_at
                from supplier_brand b
                left join basedata_brand bd on bd.id = b.brand_id
                where b.org_id = ? and b.supplier_id = ?
                order by b.is_primary desc, b.id
                """, orgId, supplierId);
    }

    /** 厂商摘要行(供详情抽屉头部复用)。 */
    public Map<String, Object> summary(long orgId, long supplierId) {
        Map<String, Object> supplier = supplierService.load(orgId, supplierId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", ((Number) supplier.get("id")).longValue());
        m.put("no", supplier.get("no"));
        m.put("name", supplier.get("name"));
        return m;
    }
}
