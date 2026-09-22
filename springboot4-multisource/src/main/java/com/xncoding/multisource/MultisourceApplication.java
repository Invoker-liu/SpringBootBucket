package com.xncoding.multisource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 多数据源配置演示。
 *
 * <p>这个工程里同时放了两套方案，方便对照：
 * <ul>
 *   <li><b>静态多数据源</b>——两个 {@code DataSource}、两套 MyBatis bean，
 *       mapper 按包名分流，见 {@code config.PosDataSourceConfig} / {@code config.BizDataSourceConfig}；
 *   <li><b>动态路由数据源</b>——一个 {@code AbstractRoutingDataSource}，
 *       靠 {@code @DataSource} 注解 + AOP 在运行时切换，见 {@code config.DynamicDataSourceConfig}。
 * </ul>
 *
 * <p><b>注意这里没有 {@code @MapperScan}。</b>多数据源下 mapper 分散在三个不同的
 * {@code SqlSessionFactory} 上，统一扫描会全部绑到同一个（或者干脆启动失败）。
 * 三个 mapper 包分别由各自的配置类用 {@code @MapperScan(sqlSessionFactoryRef = ...)} 声明。
 */
@SpringBootApplication
public class MultisourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultisourceApplication.class, args);
    }
}
