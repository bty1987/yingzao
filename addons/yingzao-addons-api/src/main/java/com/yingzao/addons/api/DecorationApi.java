package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 装修单域对插件开放的只读契约(实现在 addon-decoration,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供业务插件(如报价)在挂单时校验装修单归属并取展示快照,
 * 与 CustomerProfileApi 同一纪律:主数据唯一写点,跨插件只读契约。
 */
public interface DecorationApi {

    /** 装修单摘要(只读);不存在或不属本组织返回 empty。 */
    Optional<OrderSummary> queryOrder(long orgId, long orderId);

    /**
     * 装修单摘要快照(label 为房屋展示快照,与主单 house_label 同源)。
     * status = 合同线;projectStatus = 工程线(0 未开工/1 施工中/2 竣工——工程验收后
     * 外辅登记锁定等场景的判断依据,禁消费方直查工程表)。
     */
    record OrderSummary(long orderId, String orderNo, String customerName, String houseLabel,
                        int type, int status, int projectStatus,
                        long designerId, long salesmanId) {
    }

    /**
     * 装修单归属客户只读查询(addon-member 积分记分按款项 biz 三元组反查客户;
     * 不存在或不属本组织返回 empty)。只读契约,禁消费方直查装修单表。
     */
    Optional<CustomerRef> queryOrderCustomer(long orgId, long orderId);

    /** 装修单归属客户引用(只读)。 */
    record CustomerRef(long customerId, String customerName) {
    }

    /**
     * 客户名下装修单列表(只读;C 端「我的装修单」入口数据源,归属=主单 customer_id)。
     * 废单(5)不返回;按登记倒序。
     */
    java.util.List<OrderBrief> listByCustomer(long orgId, long customerId);

    /** 客户视角装修单摘要(只读投影;零金额零人员)。 */
    record OrderBrief(long orderId, String orderNo, String houseLabel, int status, int projectStatus) {
    }
}
