package com.xncoding.mongo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 启动类。
 * <p>
 * {@code @EnableMongoAuditing} 对应上一篇的 {@code @EnableJpaAuditing}，
 * 用来打开 Spring Data 的审计支持，配合文档上的 {@code @CreatedDate} /
 * {@code @LastModifiedDate}，让创建时间和更新时间由框架自动填。
 * <b>它同样是全局开关</b>，打开之后所有带审计注解的文档都会走，
 * 不需要也不应该每个类各配一遍。
 * <p>
 * 注意这个注解来自 {@code org.springframework.data.mongodb.config} 包，
 * 和 JPA 的 {@code org.springframework.data.jpa.repository.config.EnableJpaAuditing}
 * 不是一套。审计那套接口（{@code DateTimeProvider}、{@code AuditingHandler}）
 * 是 Spring Data Commons 提供的，但开关各模块自己一个。
 * <p>
 * 本篇没有写 {@code @EnableMongoRepositories}：仓储接口放在启动类的子包下，
 * {@code DataMongoRepositoriesAutoConfiguration} 会自动扫描到。
 */
@SpringBootApplication
@EnableMongoAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class MongoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MongoApplication.class, args);
    }

    /**
     * 审计时间的时间源，把精度截到毫秒。
     * <p>
     * 上一篇（MySQL）需要这个，是因为 {@code DATETIME(3)} 只存到毫秒，
     * 不截断的话"接口刚返回的时间戳"和"从库里读回来的时间戳"对不上，
     * 客户端拿它做等值比较必然踩空。
     * <p>
     * <b>MongoDB 这边情况反过来了，但同样需要截断——理由不同。</b>
     * BSON 的日期类型本身就是 64 位整数毫秒（没有纳秒这个概念），
     * 所以写下去的瞬间框架就会把纳秒丢掉。问题在于：<b>丢掉的是写入时的值，
     * 而 Java 对象里那份内存副本还带着纳秒</b>。于是同一个 {@code OrderResponse}
     * 里，创建接口返回的 {@code createdAt} 带纳秒，紧接着 GET 一次回来的就没有，
     * 两个 JSON 长得不一样。
     * <p>
     * 在源头截到毫秒，内存值和存储值就永远一致。一行代码换掉一整类
     * "时间戳看着像但不等"的诡异问题。
     * <p>
     * 另外提一句时区：{@code LocalDateTime} 和 BSON 日期之间靠 JVM 的默认时区换算，
     * 写入和读取用的是同一个时区所以自洽，但如果应用容器和 MongoDB 宿主机时区不同，
     * 用 {@code mongosh} 直接看文档会看到差了几个小时的 UTC 值。
     * 那不是数据错了，是"没有时区的时间"跨工具解释出来的差异——
     * 这也是为什么对外契约里用 {@code LocalDateTime} 时要约定好时区。
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
