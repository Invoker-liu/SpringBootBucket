package com.xncoding.apiversion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.ApiVersionDeprecationHandler;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * v1 弃用通知：请求带 X-Api-Version: 1 时，响应自动带上
 * Deprecation / Sunset / Link 三个头，提示客户端迁移到 v2。
 * Boot 自动收集这个 bean 并挂到版本策略上。
 */
@Configuration
public class ApiVersionDeprecationConfig {

    @Bean
    public ApiVersionDeprecationHandler apiVersionDeprecationHandler() {
        StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();
        handler.configureVersion("1")
                .setDeprecationDate(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneId.of("Asia/Shanghai")))
                .setDeprecationLink(URI.create("https://example.com/docs/api-v2-migration"))
                .setSunsetDate(ZonedDateTime.of(2027, 4, 1, 0, 0, 0, 0, ZoneId.of("Asia/Shanghai")));
        return handler;
    }
}
