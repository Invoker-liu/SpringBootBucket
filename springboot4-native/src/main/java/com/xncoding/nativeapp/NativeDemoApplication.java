package com.xncoding.nativeapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AOT 与 Native Image 演示应用：紧凑的订单查询 REST 服务。
 * @ConfigurationPropertiesScan 让 OrderProperties 走显式扫描注册，AOT 处理同样生效。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NativeDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NativeDemoApplication.class, args);
    }
}
