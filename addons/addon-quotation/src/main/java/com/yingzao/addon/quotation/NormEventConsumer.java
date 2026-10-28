package com.yingzao.addon.quotation;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.ConstructionAcceptedEvent;
import com.yingzao.addons.api.event.ConstructionDrawnEvent;
import com.yingzao.addons.api.event.PaymentCollectedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 定额重算消费器(总线 SPI):三触发点汇流——
 * ①ConstructionDrawnEvent(放线成功/补录,核对清单 1)→ 全量重算;
 * ②PaymentCollectedEvent(typeCode=ar_diff,差量款收齐)→ 该差量单来源重算
 *   (🔴bizId=arId 非 orderId,第 81 次库实证;核对清单 3;事件无 orderId,从差量单反查);
 * ③ConstructionAcceptedEvent(验收竣工,核对清单 4)→ 完工固化(禁用行物理清+拒再生成)。
 * 全部动作幂等(重算幂等/固化条件更新),事件重放安全。
 * @Lazy 断环:NormService 构造器注入 YzEventBus(附加线发布回程事件),总线又收集本消费器
 * (第 89 次 AcceptanceVisitConsumer 同款,启动即死家族);JdbcTemplate 无环直接注入。
 */
@Component
public class NormEventConsumer implements YzEventConsumer {

    private final NormService norms;
    private final JdbcTemplate jdbc;

    public NormEventConsumer(@Lazy NormService norms, JdbcTemplate jdbc) {
        this.norms = norms;
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ConstructionDrawnEvent", "PaymentCollectedEvent", "ConstructionAcceptedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "ConstructionDrawnEvent" -> {
                ConstructionDrawnEvent payload = event.payloadAs(ConstructionDrawnEvent.class);
                norms.generate(payload.orgId(), payload.orderId(), null,
                        payload.operatorId() == null ? 0 : payload.operatorId());
            }
            case "PaymentCollectedEvent" -> {
                PaymentCollectedEvent payload = event.payloadAs(PaymentCollectedEvent.class);
                if ("ar_diff".equals(payload.typeCode())) {
                    long arId = Long.parseLong(payload.bizId());
                    Long orderId = jdbc.queryForObject(
                            "select order_id from quotation_add_reduce where id = ? and org_id = ?",
                            Long.class, arId, payload.orgId());
                    if (orderId == null) {
                        // 差量单已物理撤(草稿删除路径)——款收齐事件晚到,无需重算,吞掉记日志语义
                        return;
                    }
                    norms.generate(payload.orgId(), orderId, arId,
                            payload.collectorId() == null ? 0 : payload.collectorId());
                }
            }
            case "ConstructionAcceptedEvent" -> {
                ConstructionAcceptedEvent payload = event.payloadAs(ConstructionAcceptedEvent.class);
                norms.onAccepted(payload.orgId(), payload.orderId());
            }
            default -> {
            }
        }
    }
}
