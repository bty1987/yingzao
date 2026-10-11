package com.yingzao.addon.customer;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.MarketApi;
import com.yingzao.addons.api.event.InboundAcceptedEvent;

/**
 * 营销进线承接服务(获客链与客源域的桥,addon-market 发 InboundAcceptedEvent、本插件消费):
 * 单事务内 = MarketApi.beginAccept 认领(幂等闸,重放安全)→ revealPhone 解密取明文
 * (强制留痕,明文不进事件载荷)→ 建客源(chance 档再派发机会)→ acceptResult 回写。
 * 认领失败 = 已承接/已忽略,直接返回(消费确认);业务失败抛异常整体回滚,
 * 消费者 catch 后 markFailed 置失败态留原因,等待人工重试或忽略——
 * 替代老系统 REQUIRES_NEW 跨模块直调的孤儿事务(客源已建、进线消失)。
 */
@Service
public class InboundAcceptService {

    private final MarketApi market;
    private final LeadService leads;
    private final ChanceService chances;
    private final MessageSource messages;

    public InboundAcceptService(MarketApi market, LeadService leads, ChanceService chances,
            MessageSource messages) {
        this.market = market;
        this.leads = leads;
        this.chances = chances;
        this.messages = messages;
    }

    @Transactional
    public void accept(long inboundId, long orgId, String name, String phoneHmac, String acceptMode,
            String note) {
        if (!market.beginAccept(inboundId, orgId)) {
            return;
        }
        String phone = market.revealPhone(inboundId, orgId, 0L);
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException(messages.getMessage(
                    "market.accept.phoneMissing", null, LocaleContextHolder.getLocale()));
        }
        long leadId = leads.create(orgId, name == null ? "" : name, phone, "market", note);
        Long chanceId = null;
        if (InboundAcceptedEvent.MODE_CHANCE.equals(acceptMode)) {
            chanceId = chances.assign(orgId, leadId, "auto", null);
        }
        market.acceptResult(inboundId, orgId, true, leadId, chanceId, null);
    }

    /** 承接失败置态(独立于 accept 事务:accept 已回滚,这里把失败原因留在进线上可见)。 */
    @Transactional
    public void markFailed(long inboundId, long orgId, String errorMsg) {
        market.acceptResult(inboundId, orgId, false, null, null, errorMsg);
    }
}
