package com.yingzao.core.eventbus;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 投递给消费者的事件记录:任务 id + 类型 + 载荷。
 * payloadAs 反序列化为契约 record(addons-api 中的事件类);解析失败抛 IllegalStateException,
 * 由总线按失败处理(重试/死信),绝不静默吞掉。
 */
public record YzEventRecord(long taskId, String eventType, String eventKey, JsonNode payload) {

    public <T> T payloadAs(Class<T> type) {
        try {
            return EventBusObjectMapper.get().treeToValue(payload, type);
        } catch (Exception e) {
            throw new IllegalStateException("event payload deserialize failed, taskId=" + taskId
                    + " type=" + type.getName(), e);
        }
    }
}
