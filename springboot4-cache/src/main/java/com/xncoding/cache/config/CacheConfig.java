package com.xncoding.cache.config;

import com.xncoding.cache.service.OrderService;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;

/**
 * 缓存配置。
 * <p>
 * 两个最容易踩的点都在这个类里：
 * <p>
 * 1. {@code @EnableCaching} 必须自己加。Boot 4 的 CacheAutoConfiguration 挂着
 *    {@code @ConditionalOnBean(CacheAspectSupport.class)}——不开启注解驱动，
 *    连 CacheManager 都不会被装配，而且 {@code @Cacheable} 等注解会被
 *    <b>静默忽略</b>（没有代理，方法照常每次执行），不报任何错。
 * <p>
 * 2. import 的 {@link RedisCacheConfiguration} 是 spring-data-redis 的
 *    {@code org.springframework.data.redis.cache.RedisCacheConfiguration}（配
 *    序列化和 TTL 的 DSL）。Boot 自己的自动配置类
 *    {@code org.springframework.boot.cache.autoconfigure.RedisCacheConfiguration}
 *    也叫这个名字——IDE 补全时选错包，编译都过不了。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 键前缀。注意：yml 里的 spring.cache.redis.key-prefix 只作用于"全局默认配置"，
     * 一旦某个缓存名被下面的 withCacheConfiguration 整体替换，
     * 全局前缀不会自动带过来——per-cache 配置要自己再 prefixCacheNameWith 一次，
     * 否则这个缓存的键会退化成裸的 {@code orders::SO...}。
     */
    public static final String KEY_PREFIX = "sb4:cache:";

    /**
     * 每缓存名粒度的定制。spring.cache.redis.* 只能配一个全局默认；
     * 不同缓存名要不同 TTL / 不同序列化器，得在 builder 上逐个 withCacheConfiguration。
     * <p>
     * 不写这个 bean 也能跑，但 Redis 缓存管理器会用默认的
     * JDK 序列化（缓存对象必须 implements Serializable，且 Redis 里是一坨
     * 人读不了的 \xac\xed 二进制）。这里把两个缓存都换成 JSON。
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer perCacheCustomizer() {
        // 与缓存值一起写入的类型信息白名单：只允许反序列化本工程的类型，
        // 否则 default typing 是一条已知的反序列化攻击通道（07 篇同款写法）。
        // 注意"com.xncoding."不够：BigDecimal 这类非 final 的 JDK 值类型也会被
        // 写入类型 id（java.math.BigDecimal），读回时同样要过白名单，
        // 否则读缓存直接抛 SerializationException——第一笔能写、第二笔必炸
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.xncoding.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();
        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.create(
                builder -> builder.enableDefaultTyping(typeValidator));

        return builder -> builder
                // 订单缓存：JSON 序列化，TTL 10 分钟（覆盖 yml 里的全局 30m）
                .withCacheConfiguration(OrderService.CACHE_ORDERS,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .prefixCacheNameWith(KEY_PREFIX)
                                .entryTtl(Duration.ofMinutes(10))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                        .fromSerializer(jsonSerializer)))
                // 订单快照缓存：演示"不同缓存名不同 TTL"，2 秒过期（测试与演示用）
                .withCacheConfiguration(OrderService.CACHE_ORDER_FLASH,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .prefixCacheNameWith(KEY_PREFIX)
                                .entryTtl(Duration.ofSeconds(2))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                        .fromSerializer(jsonSerializer)));
    }
}
