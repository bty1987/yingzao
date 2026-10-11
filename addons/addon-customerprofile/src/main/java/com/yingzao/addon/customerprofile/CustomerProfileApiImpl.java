package com.yingzao.addon.customerprofile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.CustomerProfileApi;

/**
 * CustomerProfileApi 只读实现:供业务插件建单时校验档案归属并取展示快照。
 * 只读、零业务知识;主数据唯一写点纪律不变(军规:其余模块一律只读)。
 */
@Component
public class CustomerProfileApiImpl implements CustomerProfileApi {

    private final JdbcTemplate jdbc;

    public CustomerProfileApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerSummary> queryCustomer(long orgId, long customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, no, name from customerprofile_customer where id = ? and org_id = ?",
                customerId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new CustomerSummary(((Number) row.get("id")).longValue(),
                (String) row.get("no"), (String) row.get("name")));
    }

    @Override
    public Optional<HouseSummary> queryHouse(long orgId, long houseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select h.id, h.village_id, v.name as village_name,
                       h.built, h.unit, h.floor_num, h.door_num, h.house_type
                from customerprofile_house h
                join customerprofile_village v on v.id = h.village_id
                where h.id = ? and h.org_id = ?
                """, houseId, orgId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new HouseSummary(((Number) row.get("id")).longValue(),
                ((Number) row.get("village_id")).longValue(),
                (String) row.get("village_name"),
                (String) row.get("built"), (String) row.get("unit"),
                row.get("floor_num") == null ? null : ((Number) row.get("floor_num")).intValue(),
                row.get("door_num") == null ? null : ((Number) row.get("door_num")).intValue(),
                (String) row.get("house_type")));
    }
}
