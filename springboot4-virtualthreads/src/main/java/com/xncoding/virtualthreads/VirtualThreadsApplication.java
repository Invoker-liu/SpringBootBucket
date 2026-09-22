package com.xncoding.virtualthreads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 虚拟线程演示应用：订单汇总接口在平台线程 / 虚拟线程两档下压测对比。
 * 开关为 spring.threads.virtual.enabled，默认 false（平台线程档）。
 */
@SpringBootApplication
public class VirtualThreadsApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualThreadsApplication.class, args);
    }
}
