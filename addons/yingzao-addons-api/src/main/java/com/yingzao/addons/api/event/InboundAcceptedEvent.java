package com.yingzao.addons.api.event;

/**
 * 进线受理领域事件(契约,发布方 addon-market):营销进线登记落库后发布,addon-customer
 * 订阅走自己的建档链(建客源 source 档 / 建客源+派发机会 chance 档),完成后经
 * {@link com.yingzao.addons.api.MarketApi} 回写承接结果并落归因——替代老系统
 * REQUIRES_NEW 跨模块直调(孤儿事务:客源已建、进线消失)。
 * 建议以 eventKey = "market:inbound:{inboundId}:accepted" 做去重锚点。
 * 🔴 手机号只携带 HMAC(等值锚点),明文不进事件载荷(outbox 落库持久);承接需要明文时
 * 经 MarketApi.revealPhone 解密读取(强制留痕)。
 */
public record InboundAcceptedEvent(Long inboundId, Long orgId, String name, String phoneHmac,
                                   String acceptMode, String note) {

    /** 承接档位:仅建客源。 */
    public static final String MODE_SOURCE = "source";

    /** 承接档位:建客源 + 派发机会。 */
    public static final String MODE_CHANCE = "chance";
}
