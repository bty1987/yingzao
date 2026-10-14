package com.yingzao.addon.customerprofile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.CustomerProfileApi;

/**
 * CustomerProfileApi 实现:只读方法供业务插件建单时校验档案归属并取展示快照;
 * ensureCustomerByPhone 是本契约唯一写方法(C 端登录 get-or-create 建档),
 * 内部走 existCustomer 幂等范式(主数据唯一写点纪律保持,实现仍在 addon-customerprofile)。
 */
@Component
public class CustomerProfileApiImpl implements CustomerProfileApi {

    private final JdbcTemplate jdbc;
    private final CustomerService customers;

    public CustomerProfileApiImpl(JdbcTemplate jdbc, CustomerService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    /** C 端登录建档:existCustomer 幂等;档案名用脱敏形(C 端无姓名来源,到店后员工端改名)。 */
    @Override
    public long ensureCustomerByPhone(long orgId, String mobile) {
        String tail = mobile == null || mobile.length() < 4 ? mobile : mobile.substring(mobile.length() - 4);
        Map<String, Object> r = customers.exist(orgId, mobile, "C端客户****" + tail, null);
        return ((Number) r.get("customerId")).longValue();
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
