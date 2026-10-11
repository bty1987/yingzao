package com.yingzao.addons.api;

import java.util.List;
import java.util.Map;

/**
 * 款项域对插件开放的服务契约(实现在 addon-payment,业务插件只依赖本接口,禁止依赖其实现模块)。
 * 两类能力:①业务插件产生应收时创建款项单(签约拆分按类型字典比例模板自行算好逐项创建);
 * ②预付款科目余额的收入/划扣/查询(划扣原子扣减,余额不足抛业务异常,错误码 270000 段)。
 * 所有金额 = 厘(×1000 定点整数),恒正;biz 三元组 = (业务插件 id, 业务类型, 业务单 id) 纯溯源。
 */
public interface PaymentApi {

    /** 创建收款款项单(direction=0)。同业务对象同类型已存在(任意状态)时抛 270006——修老签约款线硬删重建。 */
    long createBill(CreateBillCmd cmd);

    /** 预付款收入(充值/回补,balance + amount),返回流水 id。 */
    long advanceCharge(AdvanceCmd cmd);

    /** 预付款划扣(balance -= amount,原子条件扣减,余额不足抛 270017),返回流水 id。 */
    long advanceDeduct(AdvanceCmd cmd);

    /** 预付款余额(厘)。科目或账户不存在时返回 0(未发生即零余额)。 */
    long advanceBalance(long orgId, String subjectCode, String refType, String refId);

    /** 款项类型字典(启用行,含顺序 sort 与比例模板 scaleTemplate JSON,签约拆分数据源)。 */
    List<Map<String, Object>> listBillTypes(long orgId);

    /** 业务对象的款项单列表(任意状态与方向;业务门槛校验/签约拆分去重用,禁直查 payment 表)。 */
    List<BillSummary> listBills(long orgId, String bizAddon, String bizType, String bizId);

    /** 作废未收款项单(仅 direction=0 且 status=0;废单/终止联动)。已收款项走退款,不在此列。 */
    void cancelBill(long orgId, long billId);

    /** 款项单摘要(业务插件视角;status 口径见 addon-payment 字典)。 */
    record BillSummary(long billId, String typeCode, String typeName, int direction,
                       long amount, int sort, int status) {
    }

    /** 创建款项单命令:typeCode 必须是组织内启用类型。 */
    record CreateBillCmd(long orgId, String bizAddon, String bizType, String bizId,
                         String typeCode, long amount, String remark) {
    }

    /** 预付款变动命令:amount 恒正;operatorId/remark 可空(插件链路无登录态时)。 */
    record AdvanceCmd(long orgId, String subjectCode, String refType, String refId, long amount,
                      String bizAddon, String bizType, String bizId, Long operatorId, String remark) {
    }
}
