package com.xncoding.redis.service;

import com.xncoding.redis.dto.VisitStatsResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 访问统计服务：演示 StringRedisTemplate 与原子自增。
 * <p>
 * 与 {@link CaptchaService} 故意用了两个不同的模板：
 * <ul>
 *   <li>{@code RedisTemplate<String, Object>} —— value 是任意对象，走 JSON 序列化；</li>
 *   <li>{@code StringRedisTemplate} —— value 也是字符串，直接对应 Redis 的文本世界，
 *       自动配置已经给了一个现成的 bean。</li>
 * </ul>
 * 计数器、Set 这类"值本身就是简单字符串"的场景用后者最顺手。
 * <p>
 * 键设计：
 * <ul>
 *   <li>{@code stats:visits:total} —— 总访问计数，INCR 原子自增；</li>
 *   <li>{@code stats:visitors:{date}} —— 当日访客邮箱集合，SADD 去重 + SCARD 计数，
 *       键里带日期，隔天自然切新集合（旧集合可以再挂一个 TTL 清理）。</li>
 * </ul>
 */
@Service
public class VisitStatsService {

    private static final String KEY_TOTAL = "stats:visits:total";
    private static final String KEY_VISITORS = "stats:visitors:";

    private final StringRedisTemplate redisTemplate;

    public VisitStatsService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记录一次访问并返回统计。
     * <p>
     * {@code increment} 对应 INCR：Redis 单线程执行，两个并发请求分别得到
     * 1001 和 1002，绝不重复计数。如果这里用"读出来加一再写回去"，
     * 并发下就会丢计数 —— 这就是 INCR 存在的意义。
     */
    public VisitStatsResponse recordVisit(String email) {
        Long total = redisTemplate.opsForValue().increment(KEY_TOTAL);

        LocalDate today = LocalDate.now();
        Long unique = 0L;
        if (email != null && !email.isBlank()) {
            redisTemplate.opsForSet().add(KEY_VISITORS + today, email);
            unique = redisTemplate.opsForSet().size(KEY_VISITORS + today);
        }

        return new VisitStatsResponse(total == null ? 0 : total, today, unique == null ? 0 : unique);
    }

    /**
     * 只读统计，不产生访问记录。
     */
    public VisitStatsResponse peek() {
        String total = redisTemplate.opsForValue().get(KEY_TOTAL);
        LocalDate today = LocalDate.now();
        Long unique = redisTemplate.opsForSet().size(KEY_VISITORS + today);
        return new VisitStatsResponse(
                total == null ? 0 : Long.parseLong(total),
                today,
                unique == null ? 0 : unique);
    }
}
