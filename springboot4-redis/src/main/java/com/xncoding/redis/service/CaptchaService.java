package com.xncoding.redis.service;

import com.xncoding.redis.domain.CaptchaRecord;
import com.xncoding.redis.exception.BusinessException;
import com.xncoding.redis.exception.ResourceNotFoundException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 验证码服务：演示 Redis 最典型的三件事 —— 带 TTL 的写入、
 * 原子化的"不存在才写"（setIfAbsent）、以及 JSON 序列化的对象存取。
 * <p>
 * 键设计：
 * <ul>
 *   <li>{@code captcha:{email}} —— 验证码本体，TTL 5 分钟，到点 Redis 自己删；</li>
 *   <li>{@code captcha:limit:{email}} —— 重发冷却标记，TTL 60 秒。</li>
 * </ul>
 * 两个键的过期时间就是两条业务规则本身：不需要定时任务扫表，
 * "过期"这个概念由 Redis 原生承担 —— 这是把状态放进 Redis 和放进数据库
 * 最根本的思维差异。
 */
@Service
public class CaptchaService {

    /** 验证码有效期：5 分钟 */
    static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);

    /** 重发冷却：60 秒 */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** 最多允许失败的校验次数，超过即作废 */
    static final int MAX_ATTEMPTS = 5;

    /** 验证码键前缀 {@code captcha:}（测试也引用） */
    public static final String KEY_CAPTCHA = "captcha:";

    /** 重发冷却键前缀 {@code captcha:limit:}（测试也引用） */
    public static final String KEY_LIMIT = "captcha:limit:";

    private final RedisTemplate<String, Object> redisTemplate;

    public CaptchaService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送验证码。60 秒内重复发送直接拒绝。
     * <p>
     * {@code setIfAbsent} 对应 Redis 的 SET NX 命令：键不存在才写入，
     * 整个动作在 Redis 侧一次完成，天然抗并发 —— 两个请求同时进来，
     * 只有一个能拿到 true，不需要锁，也不需要事务。
     */
    public CaptchaSendResult send(String email) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(KEY_LIMIT + email, "1", RESEND_COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "发送过于频繁，请 60 秒后再试");
        }

        String code = "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        CaptchaRecord record = new CaptchaRecord(code, Instant.now());
        redisTemplate.opsForValue().set(KEY_CAPTCHA + email, record, CAPTCHA_TTL);

        return new CaptchaSendResult(email, CAPTCHA_TTL.toSeconds(), RESEND_COOLDOWN.toSeconds());
    }

    /**
     * 查询验证码状态（不消费）。过期后键已被 Redis 删除，这里自然 404。
     */
    public CaptchaStatusResult status(String email) {
        String key = KEY_CAPTCHA + email;
        CaptchaRecord record = read(email);
        Long ttl = redisTemplate.getExpire(key);
        return new CaptchaStatusResult(email, ttl == null ? 0 : ttl,
                Math.max(0, MAX_ATTEMPTS - record.getAttempts()), record.getCreatedAt());
    }

    /**
     * 状态查询结果：剩余秒数与剩余尝试次数。
     */
    public record CaptchaStatusResult(String email, long remainingSecond,
                                      int remainingAttempts, Instant createdAt) {
    }

    /**
     * 校验验证码。错了累加尝试次数（保持剩余 TTL 不变），对了就删键。
     * <p>
     * 注意这里的读-改-写不是原子的：并发校验同一邮箱时 attempts 可能少算。
     * 对验证码这个场景无所谓（多错一次的代价可以忽略），但要知道它不原子——
     * 需要严格原子计数时用 INCR（见 {@link VisitStatsService}）。
     */
    public boolean verify(String email, String code) {
        String key = KEY_CAPTCHA + email;
        CaptchaRecord record = read(email);

        if (record.getCode().equals(code)) {
            redisTemplate.delete(key);
            return true;
        }

        record.setAttempts(record.getAttempts() + 1);
        if (record.getAttempts() >= MAX_ATTEMPTS) {
            // 错满 5 次直接作废，防止无限暴力尝试
            redisTemplate.delete(key);
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "失败次数过多，验证码已作废，请重新获取");
        }

        // 写回时把剩余 TTL 一起带上；不带 TTL 的 set 会把过期时间清掉，
        // 让一个本该 5 分钟过期的键变成永久键 —— 这是 Redis 应用最经典的线上事故之一
        Long remaining = redisTemplate.getExpire(key);
        redisTemplate.opsForValue().set(key, record, remainingTtl(remaining));
        return false;
    }

    /**
     * 手动作废验证码（删除键）。不存在时同样 404。
     */
    public void invalidate(String email) {
        read(email);
        redisTemplate.delete(KEY_CAPTCHA + email);
    }

    private CaptchaRecord read(String email) {
        Object value = redisTemplate.opsForValue().get(KEY_CAPTCHA + email);
        if (value == null) {
            throw new ResourceNotFoundException("captcha", email,
                    "验证码不存在或已过期，请重新获取");
        }
        if (value instanceof CaptchaRecord record) {
            return record;
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "验证码记录类型异常，无法读取");
    }

    private Duration remainingTtl(Long remainingSeconds) {
        if (remainingSeconds == null || remainingSeconds <= 0) {
            return CAPTCHA_TTL;
        }
        return Duration.ofSeconds(remainingSeconds);
    }

    /**
     * 发送结果。
     */
    public record CaptchaSendResult(String email, long expiresInSecond, long resendAfterSecond) {
    }
}
