package com.xncoding.resilience;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * 内置弹性示例工程入口。
 *
 * <p>Boot 4.1.1 没有 resilience 专属 starter 与自动配置（spring-boot-autoconfigure
 * 的 AutoConfiguration.imports 中无相关条目），必须手动声明 @EnableResilientMethods，
 * 它会导入 ResilientMethodsConfiguration，注册重试与并发限制两个通知后处理器，
 * 底层依赖 spring-aop 代理。
 */
@SpringBootApplication
@EnableResilientMethods
public class ResilienceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResilienceApplication.class, args);
    }
}
