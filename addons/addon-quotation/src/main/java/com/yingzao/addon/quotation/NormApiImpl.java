package com.yingzao.addon.quotation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.NormApi;

/**
 * 定额聚合契约实现(只读;消费方 addon-construction 节点工费/工日列,后续工资域/领料对账)。
 * 计入口径与 NormService.summary 同源:非附加行 status=0 + 附加行 status=2(拍板 5);
 * 工费公式老 amountNormriceItemForSalary 逐字对齐(atttype 支向取负;trannum=0 老特判不乘)。
 */
@Component
public class NormApiImpl implements NormApi {

    /** 计入行谓词(全族统一:非附加初始行 + 附加已确认行;禁用行=重算换版中间态恒不计)。 */
    private static final String IN_SCOPE = """
            ((source_type != 3 and status = 0) or (source_type = 3 and status = 2))""";

    private final JdbcTemplate jdbc;

    public NormApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long laborFeeByNode(long orgId, long orderId, long nodeId) {
        BigDecimal fee = jdbc.queryForObject("""
                select sum(case when atttype = 0 then salary else -salary end
                    * if(trannum = 0, 1, num / 1000 * trannum / 1000))
                from quotation_norm_item
                where org_id = ? and order_id = ? and kind = ? and node_id = ? and %s"""
                .formatted(IN_SCOPE), BigDecimal.class, orgId, orderId,
                NormService.KIND_LABOR, nodeId);
        return fee == null ? 0 : fee.longValue();
    }

    @Override
    public long workdaysByNode(long orgId, long orderId, long nodeId) {
        BigDecimal days = jdbc.queryForObject("""
                select sum(workdays) from quotation_norm_item
                where org_id = ? and order_id = ? and kind = ? and node_id = ? and %s"""
                .formatted(IN_SCOPE), BigDecimal.class, orgId, orderId,
                NormService.KIND_TIME, nodeId);
        return days == null ? 0 : days.longValue();
    }

    @Override
    public List<MaterialSummaryRow> materialSummaryByOrder(long orgId, long orderId) {
        // 行级 HALF_UP 取整后累加(A5 千分位先例;num×norm_num=明细量×定额量)
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select goods_id, goods_name, unit, atttype, num, norm_num
                from quotation_norm_item
                where org_id = ? and order_id = ? and kind = ? and goods_id is not null and %s
                order by goods_id, id"""
                .formatted(IN_SCOPE), orgId, orderId, NormService.KIND_MATERIAL);
        Map<Long, MaterialSummaryRow> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long goodsId = ((Number) row.get("goods_id")).longValue();
            long quantity = NormService.milliMul(((Number) row.get("num")).longValue(),
                    row.get("norm_num") == null ? 0 : ((Number) row.get("norm_num")).longValue());
            if (((Number) row.get("atttype")).intValue() == NormService.ATT_OUT) {
                quantity = -quantity;
            }
            MaterialSummaryRow prev = acc.get(goodsId);
            if (prev == null) {
                acc.put(goodsId, new MaterialSummaryRow(goodsId, (String) row.get("goods_name"),
                        (String) row.get("unit"), quantity, 1));
            } else {
                acc.put(goodsId, new MaterialSummaryRow(prev.goodsId(), prev.goodsName(),
                        prev.unit(), prev.quantity() + quantity, prev.rowCount() + 1));
            }
        }
        return new ArrayList<>(acc.values());
    }
}
