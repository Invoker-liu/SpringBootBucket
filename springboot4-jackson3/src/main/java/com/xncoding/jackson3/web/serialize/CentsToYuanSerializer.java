package com.xncoding.jackson3.web.serialize;

import com.xncoding.jackson3.service.OrderService;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 金额分转元序列化器：内部一律存分（long），对外一律输出元（两位小数）。
 * Jackson 3 的基类是 tools.jackson.databind.ValueSerializer，
 * 上下文参数类型是 SerializationContext，异常不再声明 throws。
 */
public class CentsToYuanSerializer extends ValueSerializer<Long> {

    @Override
    public void serialize(Long cents, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeNumber(OrderService.centsToYuan(cents));
    }
}
