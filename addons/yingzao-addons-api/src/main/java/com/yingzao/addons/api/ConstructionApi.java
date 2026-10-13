package com.yingzao.addons.api;

/**
 * 工程域对插件开放的只读契约(实现在 addon-construction,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。与 DecorationApi/SalesApi 同一纪律:跨插件只读契约。
 */
public interface ConstructionApi {

    /**
     * 决算节点检查(老 ConstructionSchedulePO.node.account 口径):该装修单施工排期中
     * 参与决算的节点(account=1 快照)是否全部完工。无施工单或无决算节点时
     * allFinished=false(老 hasAccountNode=false → FAIL 同口径:没开工不配决算)。
     * 消费方:装修决算六项检查「施工」子项。
     */
    AccountNodesCheck accountNodes(long orgId, long orderId);

    /** 决算节点检查结果:total/finished = 参与决算节点总数与已完工数,firstPendingName = 首个未完工节点名。 */
    record AccountNodesCheck(boolean allFinished, int total, int finished, String firstPendingName) {
    }
}
