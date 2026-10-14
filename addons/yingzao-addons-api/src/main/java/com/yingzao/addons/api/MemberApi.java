package com.yingzao.addons.api;

import java.util.List;

/**
 * 会员积分域对插件开放的只读契约(实现在 addon-member,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供 C 端 portal 聚合口取客户本人积分视图
 * (只读投影:分项余额+可用口径+流水摘要),与 DecorationApi/SalesApi 同一纪律。
 */
public interface MemberApi {

    /**
     * 客户积分视图(只读):分项余额 + 可用口径(过期批次不倒入可用,member available 闸同源)
     * + 最近流水摘要。无账户返回空 items/records(未开户不报错)。
     */
    PointView points(long orgId, long customerId);

    /** 分项余额行:pointType 为协议键(如 decoration 销装修分);available 已扣过期批次。 */
    record PointItem(String pointType, long balance, long available) {
    }

    /** 流水摘要行(倒序前 N):num 正=记入/负=消耗。 */
    record PointRecord(long id, String pointType, long num, String sourceType, String createdAt) {
    }

    /** 积分视图容器。 */
    record PointView(List<PointItem> items, List<PointRecord> records) {
    }
}
