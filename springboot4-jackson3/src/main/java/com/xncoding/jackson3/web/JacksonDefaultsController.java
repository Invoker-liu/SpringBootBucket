package com.xncoding.jackson3.web;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jackson 3 默认值探针：Boot 容器里那个 JsonMapper 的 Feature 现状。
 * 取值单与测试断言都以这里的实测输出为准。
 */
@RestController
@RequestMapping("/api/jackson")
public class JacksonDefaultsController {

    private final ObjectMapper mapper;

    public JacksonDefaultsController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/defaults")
    public Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapperClass", mapper.getClass().getName());
        out.put("failOnUnknownProperties",
                mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        out.put("failOnNullForPrimitives",
                mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
        out.put("failOnEmptyBeans",
                mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));
        return out;
    }
}
