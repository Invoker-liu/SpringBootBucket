package com.xncoding.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 授权规则（路径级）+ 认证方式（httpBasic）+ 会话与 CSRF 策略。
     *
     * 工程里声明了自己的 SecurityFilterChain bean，Boot 的默认链
     * （@ConditionalOnDefaultWebSecurity）整条退让，这里就是唯一的授权入口。
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemWriter problems) throws Exception {
        http
            // 纯 API + STATELESS 会话：没有浏览器表单、没有 Cookie 会话，CSRF 保护没有作用对象
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // 审计记录只有管理员能读
                    .requestMatchers("/api/audit/**").hasRole("ADMIN")
                    // 订单查询与操作两种角色都放行，ADMIN 之外的收敛在服务层方法上做
                    .requestMatchers("/api/orders/**").hasAnyRole("OPERATOR", "ADMIN")
                    .anyRequest().authenticated())
            // 取舍：管理后台是纯 API，调用方是前端脚本与运维脚本，没有登录页可跳，
            // formLogin 的默认登录页与重定向对脚本不友好，httpBasic 每次请求都带凭据，状态干净。
            // entry point 必须挂在 httpBasic 上：凭据错误的 401 由 BasicAuthenticationFilter
            // 直接调它的 entry point 写出，exceptionHandling 里配的拦不到这一条路
            .httpBasic(basic -> basic.authenticationEntryPoint(problems::unauthorized))
            // 授权失败（未登录 / 已登录无权）走这里的入口与拒绝处理器（RFC 9457 风格响应体）
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden));
        return http.build();
    }

    /**
     * DelegatingPasswordEncoder：按密文前缀 {bcrypt} / {noop} / {pbkdf2} 分发算法。
     * 新密文 encode 出来默认落 {bcrypt}，老密文是什么前缀就用什么算法校验。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * JDBC 用户提供。工程声明了 UserDetailsService bean 后，
     * Boot 的 UserDetailsServiceAutoConfiguration（spring.security.user.* 内存用户）退让。
     */
    @Bean
    UserDetailsManager userDetailsService(DataSource dataSource) {
        JdbcUserDetailsManager users = new JdbcUserDetailsManager(dataSource);
        users.setUsersByUsernameQuery(
                "SELECT username, password, enabled FROM sec_users WHERE username = ?");
        users.setAuthoritiesByUsernameQuery(
                "SELECT username, CONCAT('ROLE_', role) FROM sec_users WHERE username = ?");
        return users;
    }
}
