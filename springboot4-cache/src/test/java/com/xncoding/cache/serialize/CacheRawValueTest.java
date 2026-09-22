package com.xncoding.cache.serialize;

import com.xncoding.cache.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 直读 Redis 验证缓存值的真实形态：
 * <ul>
 *   <li>orders 缓存存的是带类型信息的 JSON（而不是默认的 JDK 序列化二进制）</li>
 *   <li>不同缓存名各自拿到自己的 TTL（orders 10 分钟 / order-flash 2 秒）</li>
 *   <li>键带 sb4:cache: 前缀（per-cache 配置里显式 prefixCacheNameWith）</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheRawValueTest {

    private static final String PREFIX = "sb4:cache:";

    @Autowired
    private OrderService orderService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void requireRedis(@Autowired RedisConnectionFactory connectionFactory) {
        try {
            connectionFactory.getConnection().ping();
        } catch (Exception e) {
            assumeTrue(false, "Redis 不可达，跳过集成测试: " + e.getMessage());
        }
    }

    @Test
    void ordersCacheStoresJsonWithTypeInfo() {
        String orderNo = orderService.create("桌面吸尘器", new BigDecimal("89.50")).getOrderNo();
        orderService.getOrder(orderNo);

        String key = PREFIX + OrderService.CACHE_ORDERS + "::" + orderNo;
        String raw = redisTemplate.opsForValue().get(key);
        assertThat(raw).as("缓存条目应存在").isNotNull();
        // 带 default typing 的 JSON：开头是 { 且带 @class 字段——
        // 如果看到 \u00AC\u00ED（0xAC 0xED）开头，说明走的是 JDK 序列化
        assertThat(raw).startsWith("{\"@class\":\"com.xncoding.cache.domain.Order\"");
        assertThat(raw).contains("\"orderNo\":\"" + orderNo + "\"");

        // TTL 应为 per-cache 定制的 10 分钟（600s），而不是全局的 30 分钟
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(600L);
    }

    @Test
    void flashCacheHasShortTtl() throws Exception {
        String orderNo = orderService.create("磁吸充电宝", new BigDecimal("129.00")).getOrderNo();
        orderService.getOrderFlash(orderNo);

        String key = PREFIX + OrderService.CACHE_ORDER_FLASH + "::" + orderNo;
        assertThat(redisTemplate.opsForValue().get(key)).isNotNull();
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(2L);
    }
}
