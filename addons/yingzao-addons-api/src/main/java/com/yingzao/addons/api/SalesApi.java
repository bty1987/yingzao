package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 销售域对插件开放的只读契约(实现在 addon-sales,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供工程域开工/完工比例闸聚合装修单名下销售款进度,
 * 与 DecorationApi/QuotationApi 同一纪律:跨插件只读契约。
 */
public interface SalesApi {

    /**
     * 装修单名下全部销售单的销售款收款比例(千分比 ‰,0-1000):
     * Σ(已收+已确认款项) / Σ(未作废应收款项)。无销售单或无应收返回 0。
     * 消费方:施工开工/完工销售款闸(stacheck/fincheck),禁直查销售表与款项表。
     */
    long collectedRatio(long orgId, long orderId);

    /**
     * 销售单归属客户只读查询(addon-member 主材积分记分按款项 biz 三元组反查客户;
     * 销售单主数据只存客户名快照,归属客户经装修单解析)。不存在或不属本组织返回 empty。
     */
    Optional<DecorationApi.CustomerRef> queryOrderCustomer(long orgId, long salesOrderId);

    /** 销售单摘要(只读;orderId = 挂接装修单)。 */
    record SalesOrderRef(long salesOrderId, String orderNo, long orderId, String customerName) {
    }

    /** 销售单摘要查询(不存在或不属本组织返回 empty)。 */
    Optional<SalesOrderRef> querySalesOrder(long orgId, long salesOrderId);

    /**
     * 装修单名下未完结销售单数(老 countSalesForUnfinishByOrderId 口径:
     * 主单轴 status not in {2 全退, 4 废单, 5 完结})。消费方:装修决算六项检查「主材销售」子项。
     */
    int countUnfinishedOrders(long orgId, long orderId);

    /**
     * 装修单名下全部销售单摘要(只读;C 端资金流水主材款投影数据源):
     * 含完结单(已付历史仍需可见),剔除全退/倒单/废单。按 id 倒序(新单在前)。
     */
    java.util.List<SalesOrderRef> listByDecorationOrder(long orgId, long orderId);

    /**
     * 主材开单业绩·月额(只读;target 主材业绩线实绩数据源):该组织当月(按单据
     * created_at)全部销售单 total 之和,剔除全退/倒单/废单(status 2/3/4),
     * 含完结单(已开单业绩即计入,与「业绩=创建日原额排除废单」口径一致)。单位:厘。
     */
    long materialSalesTotalByMonth(long orgId, String yearMonth);

    /** 主材开单业绩·区间额(只读;阶段实绩):按单据 created_at 落于 [begin, end] 闭区间,口径同月额。 */
    long materialSalesTotalBetween(long orgId, String beginDay, String endDay);
}
