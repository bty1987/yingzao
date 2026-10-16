package com.yingzao.addons.api;

import java.util.Optional;

/**
 * 定材包定额对插件开放的只读契约(实现在 addon-basedata,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。供领料单做「领料定材校验」(老 checkAccessoriesItem
 * 260082 口径收编:已领累计 + 本次领用 ≤ 定额量 + 定材浮动量),与 GoodsApi 同一纪律。
 *
 * 量纲约定:quotaNum/surplus 与领料 num 同为千分位(×1000)口径,两侧直接比较不做换算——
 * 老系统靠 storenum×1000 折算埋过恒假/量纲虚增两颗雷,此处显式声明同量纲。
 */
public interface MaterialQuotaApi {

    /**
     * 定材包成员定额查询:该包是否含此商品、含则给出定额量。不存在(包无此成员/包不存在)返回 empty
     * ——调用方语义=「非定材项不拦」(老:noods.getCalc() != CALC 或 无 nid 即不校验)。
     */
    Optional<PackageQuota> queryQuota(long orgId, long packageId, long goodsId);

    /** 定材包摘要(校验包存在与状态;released=false 表示已停用)。 */
    Optional<PackageRef> queryPackage(long orgId, long packageId);

    /** 成员定额:quotaNum=成员定额量(千分位),surplus=包浮动量(千分位,引用行可改)。 */
    record PackageQuota(long packageId, long goodsId, long quotaNum, long surplus) {
    }

    /** 定材包摘要(name 为展示快照,status 0 启用/1 停用)。 */
    record PackageRef(long packageId, String name, long surplus, int status) {
    }
}
