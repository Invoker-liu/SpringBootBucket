package com.xncoding.jackson3.web.deserialize;

import com.xncoding.jackson3.service.OrderService;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;


/**
 * 金额元转分反序列化器：请求体里写 241.82（元），落进字段的是 24182（分）。
 * Jackson 3 的基类是 tools.jackson.databind.ValueDeserializer，
 * 上下文参数类型是 DeserializationContext。
 */
public class YuanToCentsDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) {
        return OrderService.yuanToCents(p.getDecimalValue());
    }
}
