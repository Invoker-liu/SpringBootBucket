package com.xncoding.orderapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示应用。包名刻意用 com.xncoding.orderapp，与 starter 的
 * com.xncoding.starter.order.notify 完全错开，避免组件扫描把自动配置类再扫一遍。
 */
@SpringBootApplication
public class OrderNotifyAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderNotifyAppApplication.class, args);
    }
}
