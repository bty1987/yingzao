package com.yingzao.addon.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.MemberApi;

/**
 * MemberApi 只读契约实现(member 侧):C 端 portal 积分视图取数。
 * 分项余额/可用口径复用 MemberPointService(过期批次不倒入可用,同源);
 * 流水摘要倒序前 20 行,零敏感字段(不携 biz 明细)。
 */
@Component
public class MemberApiImpl implements MemberApi {

    private static final List<Integer> POINT_TYPES = List.of(1, 2, 3);
    private static final Map<Integer, String> TYPE_NAMES = Map.of(1, "decoration", 2, "sales", 3, "manual");

    private final JdbcTemplate jdbc;
    private final MemberPointService points;

    public MemberApiImpl(JdbcTemplate jdbc, MemberPointService points) {
        this.jdbc = jdbc;
        this.points = points;
    }

    @Override
    public PointView points(long orgId, long customerId) {
        List<PointItem> items = new ArrayList<>();
        for (Integer type : POINT_TYPES) {
            long balance = points.balance(orgId, customerId, type);
            if (balance == 0) {
                continue;
            }
            items.add(new PointItem(TYPE_NAMES.get(type), balance,
                    points.available(orgId, customerId, type)));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, point_type, delta, src_type, created_at
                from member_point_record
                where org_id = ? and customer_id = ?
                order by id desc limit 20
                """, orgId, customerId);
        List<PointRecord> records = rows.stream()
                .map(r -> new PointRecord(((Number) r.get("id")).longValue(),
                        TYPE_NAMES.get(((Number) r.get("point_type")).intValue()),
                        ((Number) r.get("delta")).longValue(),
                        (String) r.get("src_type"),
                        r.get("created_at") == null ? null : String.valueOf(r.get("created_at"))))
                .toList();
        return new PointView(items, records);
    }
}
