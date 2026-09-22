package com.xncoding.aop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AOP 演示应用。不需要 @EnableAspectJAutoProxy：
 * 引入 spring-boot-starter-aspectj 后，AopAutoConfiguration 会按条件替工程贴上它
 * （spring.aop.proxy-target-class 默认 true，走 CGLIB 类代理）。
 */
@SpringBootApplication
public class AopApplication {

    public static void main(String[] args) {
        SpringApplication.run(AopApplication.class, args);
    }
}
