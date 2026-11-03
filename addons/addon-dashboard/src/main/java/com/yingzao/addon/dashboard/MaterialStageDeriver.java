package com.yingzao.addon.dashboard;

/**
 * 主材销售五阶段判定(W1 工作台族,docs/prd/addon-dashboard-workbench.md §三.1):
 * 装修单维度的主材销售阶段推导——报价跟进 → 签约待开工 → 硬装施工 → 安装决算 → 完结。
 *
 * <p>零新表零落库:纯函数吃各域状态快照(判定输入经既有契约拼装,数据面在 W2 聚合端点),
 * 口径改=改函数零迁移。V1 常量口径(组织级可配随 V2);重签(4)归签约待开工;
 * 完结=销售单全完结 或 装修单完工决算(status=8)。
 */
public final class MaterialStageDeriver {

    /** 阶段枚举(1..5;0=无单据不参与跟单)。 */
    public static final int STAGE_NONE = 0;
    public static final int STAGE_QUOTING = 1;
    public static final int STAGE_SIGNED = 2;
    public static final int STAGE_BUILDING = 3;
    public static final int STAGE_SETTLING = 4;
    public static final int STAGE_DONE = 5;

    private MaterialStageDeriver() {
    }

    /**
     * 判定输入快照(各域状态位,契约拼装后传入)。
     *
     * @param decorationStatus 装修单合同线状态(V501:0初始/1定金/2预签/3签约/4重签/5废单/6终止/7决算/8完工)
     * @param projectStatus    装修单工程线状态(0初始/1开工/2完工)
     * @param hasActiveQuote   存在未定稿报价单(attstatus&lt;3,经 QuotationApi 契约)
     * @param salesTotal       名下销售单数(剔除废退单,status not in 2/3/4)
     * @param salesFinished    其中已完结数(sales status=5)
     */
    public record StageInput(int decorationStatus, int projectStatus,
                             boolean hasActiveQuote, int salesTotal, int salesFinished) {
    }

    /** 判定(纯函数;终态优先——废单/终止退出跟单,完结双出口再按后段前段压序)。 */
    public static int derive(StageInput in) {
        // 废单(5)/终止(6)退出跟单(终态优先于一切过程段)
        if (in.decorationStatus() == 5 || in.decorationStatus() == 6) {
            return STAGE_NONE;
        }
        boolean signed = in.decorationStatus() == 3 || in.decorationStatus() == 4
                || in.decorationStatus() == 7 || in.decorationStatus() == 8;
        // ⑤ 完结:销售单全完结(有单且全 finish),或装修单完工决算
        if (in.salesTotal() > 0 && in.salesFinished() == in.salesTotal()
                || in.decorationStatus() == 8) {
            return STAGE_DONE;
        }
        // ④ 安装决算:工程完工,销售单未全完结
        if (in.projectStatus() == 2) {
            return STAGE_SETTLING;
        }
        // ③ 硬装施工:已开工
        if (in.projectStatus() == 1) {
            return STAGE_BUILDING;
        }
        // ② 签约待开工:合同线已签(3/4/7/8)未开工
        if (signed) {
            return STAGE_SIGNED;
        }
        // ① 报价跟进:未签约但有活跃报价;无单据=0
        return in.hasActiveQuote() ? STAGE_QUOTING : STAGE_NONE;
    }

    /** 阶段名(前端/聚合共用;零硬编码文案红线:仅后端聚合 label,页面侧另有字典)。 */
    public static String label(int stage) {
        return switch (stage) {
            case STAGE_QUOTING -> "报价跟进";
            case STAGE_SIGNED -> "签约待开工";
            case STAGE_BUILDING -> "硬装施工";
            case STAGE_SETTLING -> "安装决算";
            case STAGE_DONE -> "完结";
            default -> "未参与";
        };
    }
}
