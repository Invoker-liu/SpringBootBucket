package com.xncoding.mybatis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 * <p>
 * 这里故意没写 {@code @MapperScan}：MyBatis-Plus 的自动配置会扫描启动类所在包及其子包下
 * 标了 {@code @Mapper} 的接口，本项目所有 Mapper 都在 {@code com.xncoding.mybatis.mapper} 下，
 * 因此每个接口单独加 {@code @Mapper} 就够用了。
 * <p>
 * 需要批量扫描时注意：{@code com.baomidou.mybatisplus.annotation.MapperScan} 在 3.5.17 中
 * 已经不存在，必须用 mybatis-spring 提供的 {@code org.mybatis.spring.annotation.MapperScan}。
 */
@SpringBootApplication
public class MybatisApplication {

    public static void main(String[] args) {
        SpringApplication.run(MybatisApplication.class, args);
    }
}
