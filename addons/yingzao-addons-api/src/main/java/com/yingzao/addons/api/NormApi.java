package com.yingzao.addons.api;

import java.util.List;

/**
 * 定额子域对插件开放的只读聚合契约(实现在 addon-quotation,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。工费工资底座的三口径(PRD addon-quotation-norm R3):
 * 计入口径全族统一 = 非附加行 status=0 + 附加行 status=2(拍板 5「确认即计入,追加/手工同权」,
 * 收口老 amountNormriceItemForSalary 仅 NORMTYPE_ADD 计入的 quirk)。
 * 典型消费方:addon-construction 节点排期(工费列/工日列);后续工资域/领料对账。
 */
public interface NormApi {

    /**
     * 节点工费合计(厘,人工行 kind=1):
     * Σ(if(atttype=0,+salary,−salary) × if(trannum=0,1,num/1000×trannum/1000))——老 SQL 逐字对齐。
     * atttype=1 支向取负(减项反向);无实例行返回 0。
     */
    long laborFeeByNode(long orgId, long orderId, long nodeId);

    /** 节点工日合计(千分位行和,工时行 kind=2):Σworkdays,同计入口径;无实例行返回 0。 */
    long workdaysByNode(long orgId, long orderId, long nodeId);

    /**
     * 材料定额汇总(整单,材料行 kind=0,按商品聚合):领料对账/预算对照用。
     * quantity=Σ±(num×norm_num÷1000 HALF_UP)(A5 千分位先例,行级取整后累加),千分位量纲。
     */
    List<MaterialSummaryRow> materialSummaryByOrder(long orgId, long orderId);

    /** 材料汇总行:quantity 千分位量纲;rowCount 参与聚合的实例行数。 */
    record MaterialSummaryRow(long goodsId, String goodsName, String unit, long quantity, int rowCount) {
    }
}
