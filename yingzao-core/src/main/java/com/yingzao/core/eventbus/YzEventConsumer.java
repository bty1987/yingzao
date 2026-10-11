package com.yingzao.core.eventbus;

import java.util.Set;

/**
 * 领域事件消费者 SPI(内核平台设施):插件实现本接口并注册为 Spring bean,
 * 总线按 eventTypes() 匹配投递。与 Spring @EventListener 的区别:出站箱留痕 + 失败重试 + 死信可见。
 *
 * <p>契约:①consume 抛任何异常 = 本条投递失败,总线按退避重试,超限转死信;②同一条事件可能被
 * 重放(重试/管理端 requeue),消费者必须幂等——建议用 event.payloadAs 后的业务 id + eventKey 去重;
 * ③一个事件类型的多个消费者全部成功才算消费确认,部分失败整条重试(因此消费者务必幂等)。
 * ④消费失败不得抛出后置期望:主流程事务早已提交,这里只做旁路回写/通知。
 */
public interface YzEventConsumer {

    /** 本消费者关心的事件类型(契约类简单名,如 PaymentCollectedEvent)。 */
    Set<String> eventTypes();

    void consume(YzEventRecord event);
}
