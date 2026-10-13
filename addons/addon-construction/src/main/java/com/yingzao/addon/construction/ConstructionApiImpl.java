package com.yingzao.addon.construction;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.ConstructionApi;

/**
 * ConstructionApi 只读契约实现(construction 侧):装修决算六项检查「施工」子项取数。
 * 只读本域排期快照(account=1 快照列,点火时拷贝自节点模板),零跨域依赖。
 */
@Component
public class ConstructionApiImpl implements ConstructionApi {

    private final JdbcTemplate jdbc;

    public ConstructionApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AccountNodesCheck accountNodes(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select node_name, status from construction_schedule
                where org_id = ? and order_id = ? and account = 1
                order by sort, id
                """, orgId, orderId);
        int total = rows.size();
        int finished = 0;
        String firstPending = null;
        for (Map<String, Object> row : rows) {
            boolean done = ((Number) row.get("status")).intValue() == ConstructionService.SC_FINISH;
            if (done) {
                finished++;
            } else if (firstPending == null) {
                firstPending = (String) row.get("node_name");
            }
        }
        return new AccountNodesCheck(total > 0 && finished == total, total, finished, firstPending);
    }
}
