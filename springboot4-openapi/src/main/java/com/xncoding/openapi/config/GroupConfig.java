package com.xncoding.openapi.config;

import org.springdoc.core.models.GroupedOpenApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个文档分组：/api/orders 给前端联调，/api/admin 给运营后台。
 * 分组各自的文档在 {api-docs.path}/{group} 下，如 /v3/api-docs/orders。
 */
@Configuration
public class GroupConfig {

    @Bean
    GroupedOpenApi ordersApi() {
        return GroupedOpenApi.builder()
                .group("orders")
                .displayName("订单接口")
                .pathsToMatch("/api/orders/**")
                .build();
    }

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("订单运维接口")
                .pathsToMatch("/api/admin/**")
                .build();
    }
}
