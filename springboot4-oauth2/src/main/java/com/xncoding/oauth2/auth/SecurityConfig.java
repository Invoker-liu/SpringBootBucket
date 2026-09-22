package com.xncoding.oauth2.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 资源服务器安全链：全部接口要求 Bearer 令牌，令牌的解码与 aud 校验
 * 由 Boot 自动装配的 JwtDecoder 完成，scope 到权限的映射走默认 converter
 * （scope claim 的每个值对应一条 SCOPE_ 前缀 authority）。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, BearerProblemWriter problems) throws Exception {
        http
            // 纯 API + STATELESS 会话：没有浏览器表单、没有 Cookie 会话，
            // CSRF 保护没有作用对象
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // 演示令牌端点自带 client_id/client_secret 校验，不放进 Bearer 体系
                    .requestMatchers("/oauth2/token").permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden));
        return http.build();
    }
}
