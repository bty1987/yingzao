package com.yingzao.addons.api.event;

/**
 * 客户档案领域事件(契约,发布方 addon-customerprofile):房屋档案建档后发布。
 * 事件只携带 ID(门牌为敏感数据,消费方按 id 经档案契约查询)。
 */
public record HouseCreatedEvent(Long houseId, Long orgId, Long villageId) {
}
