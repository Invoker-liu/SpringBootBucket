package com.xncoding.openapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 纯 API 服务的过滤器链：文档端点与订单接口放行，管理接口收进 HTTP Basic。
 * swagger-ui 要发起 XHR 调 /api/admin 时会带 Authorize 按钮里保存的 Basic 凭据，
 * 同源请求没有预检，CSRF 关闭后 DELETE 才能用工具直接调。
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                            "/docs", "/docs/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().permitAll())
            .httpBasic(basic -> basic.realmName("order-service"));
        return http.build();
    }
}
