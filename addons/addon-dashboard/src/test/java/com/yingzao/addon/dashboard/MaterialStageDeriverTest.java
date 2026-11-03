package com.yingzao.addon.dashboard;

import org.junit.jupiter.api.Test;

import com.yingzao.addon.dashboard.MaterialStageDeriver.StageInput;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 五阶段判定表单测(W1,docs/prd/addon-dashboard-workbench.md §三.1 判定表全覆盖):
 * 纯函数零依赖——阶段推进/回退边界/废单终止/完结双出口/重签归类。
 */
class MaterialStageDeriverTest {

    private static StageInput in(int deco, int project, boolean quote, int salesTotal, int salesFinished) {
        return new StageInput(deco, project, quote, salesTotal, salesFinished);
    }

    @Test
    void stageTablePerPrd() {
        // ① 报价跟进:未签约+活跃报价
        assertEquals(MaterialStageDeriver.STAGE_QUOTING, MaterialStageDeriver.derive(in(0, 0, true, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_QUOTING, MaterialStageDeriver.derive(in(2, 0, true, 0, 0)));
        // ② 签约待开工:合同线已签(3/4/7/8)未开工(重签 4 归②)
        assertEquals(MaterialStageDeriver.STAGE_SIGNED, MaterialStageDeriver.derive(in(3, 0, false, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_SIGNED, MaterialStageDeriver.derive(in(4, 0, true, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_SIGNED, MaterialStageDeriver.derive(in(7, 0, false, 1, 0)));
        // ③ 硬装施工:开工
        assertEquals(MaterialStageDeriver.STAGE_BUILDING, MaterialStageDeriver.derive(in(3, 1, false, 2, 0)));
        // ④ 安装决算:工程完工,销售未全完结
        assertEquals(MaterialStageDeriver.STAGE_SETTLING, MaterialStageDeriver.derive(in(3, 2, false, 2, 1)));
        assertEquals(MaterialStageDeriver.STAGE_SETTLING, MaterialStageDeriver.derive(in(3, 2, false, 2, 0)));
        // ⑤ 完结:销售全完结 或 装修单完工决算(8)
        assertEquals(MaterialStageDeriver.STAGE_DONE, MaterialStageDeriver.derive(in(3, 2, false, 2, 2)));
        assertEquals(MaterialStageDeriver.STAGE_DONE, MaterialStageDeriver.derive(in(8, 2, false, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_DONE, MaterialStageDeriver.derive(in(7, 0, false, 1, 1)),
                "决算未开工但销售完结=完结(销售完结优先于②)");
    }

    @Test
    void noneAndTerminalStates() {
        // 无单据不参与
        assertEquals(MaterialStageDeriver.STAGE_NONE, MaterialStageDeriver.derive(in(0, 0, false, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_NONE, MaterialStageDeriver.derive(in(1, 0, false, 0, 0)));
        // 废单/终止退出跟单
        assertEquals(MaterialStageDeriver.STAGE_NONE, MaterialStageDeriver.derive(in(5, 0, true, 0, 0)));
        assertEquals(MaterialStageDeriver.STAGE_NONE, MaterialStageDeriver.derive(in(6, 1, true, 1, 0)));
    }

    @Test
    void labelsMatchStages() {
        assertEquals("报价跟进", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_QUOTING));
        assertEquals("签约待开工", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_SIGNED));
        assertEquals("硬装施工", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_BUILDING));
        assertEquals("安装决算", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_SETTLING));
        assertEquals("完结", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_DONE));
        assertEquals("未参与", MaterialStageDeriver.label(MaterialStageDeriver.STAGE_NONE));
    }
}
