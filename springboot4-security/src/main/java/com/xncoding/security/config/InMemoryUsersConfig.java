package com.xncoding.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 内存两种角色的原型版用户存储。默认 profile 不加载，启动加
 * --spring.profiles.active=memory 才生效（那时不连数据库也能登）。
 * 正式环境用 SecurityConfig 里的 JDBC 版，两种实现都是 UserDetailsService，
 * 过滤器链感知不到差别。
 */
@Configuration
@Profile("memory")
public class InMemoryUsersConfig {

    @Bean
    UserDetailsService memoryUsers(PasswordEncoder encoder) {
        UserDetails admin = User.withUsername("admin")
                .password(encoder.encode("admin123"))
                .roles("ADMIN")
                .build();
        UserDetails operator = User.withUsername("operator")
                .password(encoder.encode("operator123"))
                .roles("OPERATOR")
                .build();
        return new InMemoryUserDetailsManager(admin, operator);
    }
}
