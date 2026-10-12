package com.yingzao.addons.api.event;

/**
 * 会员活动状态领域事件(契约,发布方 addon-member):活动启停/默认切换后发布。
 * action 为业务动作协议键(enable/disable/setDefault/loseDefault)。
 * 同组织同域默认活动唯一(切换语义:新默认顶旧默认,旧默认自动让位)。
 * 经内核事件总线投递,订阅方必须幂等(eventKey = "member-activity:{activityId}:{action}")。
 */
public record ActivityStatusChangedEvent(Long activityId, Long orgId, Integer type, String action,
        Long operatorId) {
}
