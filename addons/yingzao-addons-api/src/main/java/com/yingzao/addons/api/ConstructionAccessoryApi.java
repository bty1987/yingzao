package com.yingzao.addons.api;

import java.util.List;

/**
 * 工程辅料额度对插件开放的服务契约(实现在 addon-construction,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直写其表)。领料单等业务在创建/撤销/退回时同步调用,
 * 额度闸与台账记账由施工域一次完成(修老系统 inventory 直写 construction 表 + 信任前端
 * total 的口径漂移:consume/refund 双向都按服务端重算金额)。
 * 错误码 240019-240022(240000 段,i18n 见 addon-construction)。
 */
public interface ConstructionAccessoryApi {

    /**
     * 额度校验+记账(领料创建,同步同事务):辅料库锁定 240019、总额超限 240020、
     * 登记数量超限 240021、施工单不存在 240022。total = Σ(item.total),服务端口径恒正。
     */
    void consume(ConsumeCmd cmd);

    /** 额度回退(撤销/拒绝/退回):按保存的 num/total 原样回退并落流水,不设闸。 */
    void refund(RefundCmd cmd);

    /** 单项:数量千分位,total = num×单价(厘,调用方服务端重算,禁止取前端值)。 */
    record Item(long goodsId, String goodsName, long num, long total) {
    }

    record ConsumeCmd(long orgId, long orderId, long requisitionId, String requisitionNo,
                      List<Item> items, long total, long operatorId) {
    }

    record RefundCmd(long orgId, long orderId, long requisitionId, String requisitionNo,
                     List<Item> items, long total, long operatorId) {
    }
}
