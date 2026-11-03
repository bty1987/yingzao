package com.yingzao.addons.api;

import java.util.List;
import java.util.Optional;

/**
 * 经营目标域对插件开放的只读契约(实现在 addon-target,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查 target_plan 表)。首个消费方:dashboard 工作台族 W2
 * (主材销售工作台 KPI 达成卡+目标拆分视图),与 DecorationApi/SalesApi 同一纪律。
 */
public interface TargetApi {

    /**
     * 单节点月度目标(只读):节点×月份×业绩线的启用目标(status=0,取 id 最小一条,
     * 与 TargetService.achievement 同口径);无目标返回 empty。
     */
    Optional<PlanView> planOfMonth(long orgId, long orgNode, String yearMonth, String line);

    /**
     * 月度目标列表(只读):该组织某月某业绩线全部节点目标(工作台目标拆分视图数据源,
     * 含停用目标由消费方按 status 呈现);按节点 id 升序。
     */
    List<PlanView> listPlansOfMonth(long orgId, String yearMonth, String line);

    /** 目标计划视图(amount 厘;status 0=启用/1=停用,与 target_plan 一致)。 */
    record PlanView(long planId, long orgNodeId, String orgNodeName, long amount, int status) {
    }
}
