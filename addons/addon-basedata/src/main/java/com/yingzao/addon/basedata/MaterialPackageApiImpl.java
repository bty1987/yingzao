package com.yingzao.addon.basedata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.MaterialQuotaApi;

/**
 * MaterialQuotaApi 只读契约实现(basedata 侧):领料定材校验取数(B 类#7)。
 * 只读本域(定材包主表 + 成员明细),零跨域依赖。
 */
@Component
public class MaterialPackageApiImpl implements MaterialQuotaApi {

    private final JdbcTemplate jdbc;

    public MaterialPackageApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PackageQuota> queryQuota(long orgId, long packageId, long goodsId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select i.num as item_num, p.surplus as pkg_surplus
                from basedata_material_package_item i
                join basedata_material_package p on p.id = i.package_id
                where i.package_id = ? and i.goods_id = ? and p.org_id = ?
                """, packageId, goodsId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new PackageQuota(packageId, goodsId,
                ((Number) row.get("item_num")).longValue(),
                row.get("pkg_surplus") == null ? 0L : ((Number) row.get("pkg_surplus")).longValue()));
    }

    @Override
    public Optional<PackageRef> queryPackage(long orgId, long packageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, surplus, status from basedata_material_package
                where id = ? and org_id = ?
                """, packageId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new PackageRef(((Number) row.get("id")).longValue(),
                (String) row.get("name"),
                row.get("surplus") == null ? 0L : ((Number) row.get("surplus")).longValue(),
                ((Number) row.get("status")).intValue()));
    }
}
