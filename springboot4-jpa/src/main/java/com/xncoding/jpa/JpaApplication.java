package com.xncoding.jpa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 启动类。
 * <p>
 * {@code @EnableJpaAuditing} 用来打开 Spring Data JPA 的审计支持，配合实体上的
 * {@code @CreatedDate} / {@code @LastModifiedDate} 与 {@code @EntityListeners}，
 * 让创建时间和更新时间由框架自动填。注意它是**全局开关**，一旦打开，
 * 所有带 {@code @CreatedDate} 的实体都会走审计，不需要也不应该每个实体各配一遍。
 * <p>
 * 本篇没有写 {@code @EnableJpaRepositories}：仓储接口放在启动类的子包下，
 * {@code DataJpaRepositoriesAutoConfiguration} 会自动扫描到。
 */
@SpringBootApplication
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaApplication {

    public static void main(String[] args) {
        SpringApplication.run(JpaApplication.class, args);
    }

    /**
     * 审计时间的时间源，把精度截到毫秒。
     * <p>
     * 这是上一篇踩过的同一个坑。表里的 {@code created_at} 是 {@code DATETIME(3)}，
     * 只存到毫秒；而 {@code AuditingEntityListener} 默认取的是
     * {@code LocalDateTime.now()}，带完整的纳秒。结果是同一个订单：
     * <ul>
     *   <li>创建接口返回的 {@code createdAt} 是 {@code 2026-09-18T18:39:45.4966677}；</li>
     *   <li>紧接着按 id 再查一次，数据库读回来的是 {@code 2026-09-18T18:39:45.497}。</li>
     * </ul>
     * 客户端拿"创建时返回的时间戳"去做等值比较、做 {@code If-Modified-Since}，
     * 一定会踩空。让时间在进入实体的那一刻就截断到毫秒，两边就永远一致了。
     * <p>
     * 另一种做法是把实体字段声明成 {@code @Column(columnDefinition = "DATETIME(6)")}，
     * 把精度喂到微秒。但那样只是把差异从毫秒挪到微秒，只要两边取时间的时机不同就还是会对不上，
     * 不如在源头对齐存储精度。
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
