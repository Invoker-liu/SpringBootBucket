package com.xncoding.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4 教程：声明式事务。
 *
 * <p>这个工程里【没有任何一行】事务配置。没有 {@code @EnableTransactionManagement}，
 * 没有手工 new 事务管理器，没有 XML。声明式事务是 Boot 的自动配置直接给出来的，
 * 本篇要讲的就是这份「白送的东西」在什么情况下会悄悄不工作。
 */
@SpringBootApplication
public class TransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionApplication.class, args);
    }
}
