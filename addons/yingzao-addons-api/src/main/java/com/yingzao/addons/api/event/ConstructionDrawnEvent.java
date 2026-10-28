package com.yingzao.addons.api.event;

/**
 * 放线成功领域事件(契约,发布方 addon-construction):放线成功(successDrawline)或补录成功
 * (retroactiveDrawline)并完成点火后发布——此前 construction 域成功路径无事件(仅失败事件),
 * 本事件补齐成功面;首个消费方 addon-quotation 定额子域(触发定额实例全量重算)。
 * 经内核事件总线投递,订阅方必须幂等(重算天然幂等),
 * 建议以 eventKey = "drawline:{drawlineId}:drawn"(补录 = "drawline:order:{orderId}:retroactive")做去重锚点。
 */
public record ConstructionDrawnEvent(Long drawlineId, Long orgId, Long orderId,
        boolean retroactive, Long operatorId) {
}
