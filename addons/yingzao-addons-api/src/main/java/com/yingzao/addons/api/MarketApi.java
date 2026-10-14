package com.yingzao.addons.api;

/**
 * 营销进线承接回写契约(实现在 addon-market,addon-customer 只依赖本接口,
 * 禁止依赖其实现模块与直写其表):承接链 = beginAccept 认领(幂等闸)→ 建
 * 客源/机会 → acceptResult 回写(同事务),替代老系统 REQUIRES_NEW 直调 + 归因
 * 滞留的孤儿事务。错误码 280000 段,i18n 见 addon-market。
 */
public interface MarketApi {

    /**
     * 承接认领:条件置「承接中」(仅 0 待承接可认领),返回 false = 已被承接/已忽略/不存在,
     * 消费者应直接确认(幂等闸,事件重放安全)。须与建客源/机会、acceptResult 同事务。
     */
    boolean beginAccept(long inboundId, long orgId);

    /**
     * 承接结果回写(承接中 → 成功/失败,失败原因留痕):成功落归因流水(建客源/建机会逐级
     * insert-only);失败只置态与错误信息,等待人工重试或忽略。须与建客源/机会同事务。
     */
    void acceptResult(long inboundId, long orgId, boolean success, Long leadId, Long chanceId,
                      String errorMsg);

    /** 进线手机号明文读取(建客源需要):解密强制留痕(修老报名手机明文+密文泄漏无痕)。 */
    String revealPhone(long inboundId, long orgId, long operatorId);

    /**
     * 案例库只读投影(C 端匿名浏览数据源;仅启用中案例,city 空=全部城市)。
     * 返回 {total, rows:[CaseCard]};效果图片清单不在此口径(C 端详情 V2)。
     */
    CasePage cCases(long orgId, String city, int page, int size);

    /** 案例卡片(只读,展示字段;figureName 为主案人物名)。 */
    record CaseCard(long caseId, String title, String cityName, String style,
                    String coverImage, long figureId, String figureName) {
    }

    /** 分页容器。 */
    record CasePage(long total, java.util.List<CaseCard> rows) {
    }
}
