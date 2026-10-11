package com.yingzao.core.eventbus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 事件总线专用 ObjectMapper(静态持有,序列化/反序列化契约 record 两头共用同一配置;
 * 未知字段忽略——契约加字段向后兼容,老消费者不被新字段炸掉)。
 */
final class EventBusObjectMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EventBusObjectMapper() {
    }

    static ObjectMapper get() {
        return MAPPER;
    }
}
