package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 客户档案域对插件开放的只读契约(实现在 addon-customerprofile,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供业务插件(如装修单)在建单时校验档案归属并落展示快照,
 * 档案主数据唯一写点仍在 addon-customerprofile(修老系统跨模块直写灾难的纪律延续)。
 */
public interface CustomerProfileApi {

    /** 客户摘要(只读);不存在或不属本组织返回 empty。 */
    Optional<CustomerSummary> queryCustomer(long orgId, long customerId);

    /**
     * C 端登录 get-or-create 建档(本契约唯一写方法,实现在 addon-customerprofile,
     * 走 existCustomer 幂等范式:启用中同号存在即返回,否则以脱敏名建档并留痕 source=c-portal)。
     * 返回 customerId;手机号明文由实现方加密落库,调用方不落任何 PII。
     */
    long ensureCustomerByPhone(long orgId, String mobile);

    /** 房屋摘要(只读,携楼盘名与门牌四要素明文辅助列);不存在或不属本组织返回 empty。 */
    Optional<HouseSummary> queryHouse(long orgId, long houseId);

    /** 客户摘要快照(phoneHmac 为等值关联键,不含明文 PII)。 */
    record CustomerSummary(long customerId, String no, String name) {
    }

    /** 房屋摘要快照(label = 楼盘名+栋/单元/楼层/门牌 组合展示值)。 */
    record HouseSummary(long houseId, long villageId, String villageName,
                        String built, String unit, Integer floorNum, Integer doorNum, String houseType) {

        public String label() {
            StringBuilder sb = new StringBuilder(villageName == null ? "" : villageName);
            if (built != null && !built.isBlank()) {
                sb.append(' ').append(built).append("栋");
            }
            if (unit != null && !unit.isBlank()) {
                sb.append(' ').append(unit).append("单元");
            }
            if (floorNum != null) {
                sb.append(' ').append(floorNum).append("层");
            }
            if (doorNum != null) {
                sb.append(' ').append(doorNum);
            }
            return sb.toString().trim();
        }
    }
}
