package com.yingzao.addon.customerprofile;

import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.LeadConvertedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 客源转化消费器(内核事件总线 SPI):客源 addon 转化后自动 get-or-create 客户主档。
 * 取代原 CustomerService 上的 Spring @TransactionalEventListener 进程内监听(第 38 次运行迁总线):
 * 事件出站箱留痕 + 失败重试直至死信可见,不再是"监听异常即丢"。
 * 顺带收敛一处并发隐患:exist 为查后插,并发同手机号首建档可能撞 uk(org,active_hmac)——
 * 旧监听异常即静默丢档;现在抛出 → 总线重试 → 重放时并发事务已提交,exist 直接回显既有档。
 * 幂等:exist get-or-create 天然重放安全(已存在即回显,不重复建档),符合总线 at-least-once 契约。
 */
@Component
public class LeadConvertedConsumer implements YzEventConsumer {

    private final CustomerService customers;

    public LeadConvertedConsumer(@Lazy CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LeadConvertedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        LeadConvertedEvent payload = event.payloadAs(LeadConvertedEvent.class);
        customers.exist(payload.orgId(), payload.phone(), payload.name(), null);
    }
}
