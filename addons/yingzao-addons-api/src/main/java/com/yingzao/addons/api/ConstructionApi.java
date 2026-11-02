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

    /**
     * 施工进度时间轴(只读;C 端对外脱敏视图数据源:节点名+状态+计划/实际时间,
     * 不含金额/人员/联系方式)。按排期顺序返回全部节点。
     */
    java.util.List<ProgressNode> progress(long orgId, long orderId);

    /** 进度节点(只读投影):status 0 初始/1 开工/2 完工/3 开放工费。 */
    record ProgressNode(String nodeName, int sort, int status,
                        String planStart, String planEnd, String finishTime) {
    }

    /**
     * 工地相册(只读;C 端对外投影数据源):该装修单全部施工日记带图行的图片时间倒序平铺,
     * 行内携带节点名快照(相册分组头)。 fileId 指向内核 yz_file 行,字节流由消费方
     * (portal /api/c/files/{id})经归属校验后代理。无施工单或无带图日记返回空表。
     */
    java.util.List<AlbumPhoto> customerAlbum(long orgId, long orderId);

    /** 相册照片(只读投影):fileId=yz_file.id,nodeName=日记所属节点快照,takenAt=日记时间。 */
    record AlbumPhoto(long fileId, String nodeName, String takenAt) {
    }
}
