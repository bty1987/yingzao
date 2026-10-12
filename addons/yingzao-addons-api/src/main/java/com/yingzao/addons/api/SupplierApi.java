package com.yingzao.addons.api;

import java.util.List;
import java.util.Optional;

/**
 * 厂商主数据对插件开放的只读契约(实现在 addon-supplier,业务插件只依赖本接口,
 * 禁止依赖其实现模块与直查其表)。库存外辅挂厂商、销售/结算按品牌归口厂商时,
 * 经本契约校验厂商存在且启用并取展示快照(快照纪律:落单存名称,禁跨插件直查)。
 * 修老系统"任何 uid 都能被当厂商挂上单据"与引用四形态并存(uid 外键/登录名字符串/
 * 品牌间接/裸 long)的纪律债——新写点统一为 supplier_id 外键 + 本契约校验。
 */
public interface SupplierApi {

    /** 厂商摘要(只读);不存在或不属本组织返回 empty。 */
    Optional<SupplierSummary> querySupplier(long orgId, long supplierId);

    /** 品牌关联的【启用】厂商清单(结算/选材按品牌归口的唯一口径,收敛老"三路 OR"可见性)。 */
    List<SupplierSummary> queryBrandSuppliers(long orgId, long brandId);

    /** 厂商存在且启用(业务插件挂厂商写点的闸;停用厂商拒新挂单,存量单不受影响)。 */
    boolean checkActive(long orgId, long supplierId);

    record SupplierSummary(long supplierId, String no, String name, int status) {
    }
}
