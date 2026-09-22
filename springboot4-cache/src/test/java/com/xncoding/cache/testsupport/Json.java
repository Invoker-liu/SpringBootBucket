package com.xncoding.cache.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试用的 JSON 读取小工具（Jackson 3，tools.jackson 包）。
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 从 JSON 串中读取顶层字符串字段 */
    public static String read(String json, String field) {
        JsonNode node = MAPPER.readTree(json);
        return node.get(field).asText();
    }
}
