package com.xncoding.starter.order.notify;

import tools.jackson.databind.ObjectMapper;

/**
 * Jackson 3 渲染：事件与订单号序列化成单行 JSON。仅在 classpath 上有
 * tools.jackson.databind.ObjectMapper 时由自动配置装配。
 */
public class JacksonMessageRenderer implements MessageRenderer {

    private final ObjectMapper objectMapper;

    public JacksonMessageRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String render(String event, String orderNo) {
        return objectMapper.writeValueAsString(new Payload(event, orderNo));
    }

    record Payload(String event, String orderNo) {
    }
}
