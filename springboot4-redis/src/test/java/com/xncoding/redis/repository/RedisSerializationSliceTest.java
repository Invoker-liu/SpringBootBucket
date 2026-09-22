package com.xncoding.redis.repository;

import com.xncoding.redis.config.RedisConfig;
import com.xncoding.redis.domain.CaptchaRecord;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 数据访问切片测试。
 * <p>
 * <b>先看 import 的 {@code @DataRedisTest} 包名。</b>以前它在
 * {@code org.springframework.boot.test.autoconfigure.data.redis}，Boot 4 拆模块后
 * 搬进了 {@code org.springframework.boot.data.redis.test.autoconfigure}，
 * 和 01 篇的 {@code webmvc.test.autoconfigure}、04 篇的
 * {@code data.mongodb.test.autoconfigure} 是同一套规律。
 * <p>
 * {@code @Import(RedisConfig.class)} 同样不可省：切片测试收的是
 * Spring Data Redis 那一套自动配置，自定义的 {@code @Configuration} 类
 * 不会被扫进来，不 Import 的话注入的就是自动配置那个 JDK 序列化的
 * {@code RedisTemplate<Object, Object>}，类型对不上直接编译不过——
 * 切片测试替你把关了"value 序列化器换没换"这件事。
 * <p>
 * 用的是真实 Redis（database 1），连不上就整体 skip，不做"一片红"。
 */
@DataRedisTest
@ActiveProfiles("test")
@Import(RedisConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSerializationSliceTest {

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeAll
    void requireRedis() {
        try {
            String pong = connectionFactory.getConnection().ping();
            Assumptions.assumeTrue("PONG".equals(pong), "Redis 不可用，整体跳过");
        } catch (Exception e) {
            Assumptions.abort("Redis 不可用，整体跳过: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("CaptchaRecord 经 JSON 序列化写入再读回，类型与内容都保持")
    void recordRoundTrip() {
        String key = "test:roundtrip:" + System.nanoTime();
        try {
            CaptchaRecord original = new CaptchaRecord("483920", Instant.parse("2026-09-19T10:15:30Z"));
            redisTemplate.opsForValue().set(key, original, Duration.ofMinutes(5));

            Object value = redisTemplate.opsForValue().get(key);

            // 类型还原成功：反序列化拿到的是 CaptchaRecord 而不是 Map，
            // 靠的是默认类型信息（@class 字段）。普通类才能走通这条通道。
            assertThat(value).isInstanceOf(CaptchaRecord.class);
            CaptchaRecord restored = (CaptchaRecord) value;
            assertThat(restored.getCode()).isEqualTo("483920");
            assertThat(restored.getAttempts()).isZero();
            assertThat(restored.getCreatedAt()).isEqualTo(Instant.parse("2026-09-19T10:15:30Z"));
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("写入 Redis 的 value 是带 @class 的 JSON 文本，redis-cli 可直读")
    void rawValueIsJson() {
        String key = "test:rawjson:" + System.nanoTime();
        try {
            redisTemplate.opsForValue().set(key, new CaptchaRecord("111111", Instant.now()),
                    Duration.ofMinutes(5));

            // 用 StringRedisTemplate 读原始字节：JSON 序列化的意义就在这——
            // 跨语言、跨进程都能直接读，不像 JDK 序列化是一串二进制
            String raw = stringRedisTemplate.opsForValue().get(key);
            assertThat(raw).isNotNull();
            assertThat(raw).contains("@class");
            assertThat(raw).contains("com.xncoding.redis.domain.CaptchaRecord");
            assertThat(raw).contains("\"code\":\"111111\"");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("带 TTL 写入后剩余时间在 (0, 300] 秒内")
    void ttlIsApplied() {
        String key = "test:ttl:" + System.nanoTime();
        try {
            redisTemplate.opsForValue().set(key, "hello", Duration.ofMinutes(5));
            Long ttl = redisTemplate.getExpire(key);
            assertThat(ttl).isNotNull();
            assertThat(ttl).isPositive();
            assertThat(ttl).isLessThanOrEqualTo(300);
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("INCR 原子自增：连续两次加 1，计数值不重不漏")
    void incrementIsAtomic() {
        String key = "test:incr:" + System.nanoTime();
        try {
            Long first = stringRedisTemplate.opsForValue().increment(key);
            Long second = stringRedisTemplate.opsForValue().increment(key);
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(2);
        } finally {
            redisTemplate.delete(key);
        }
    }
}
