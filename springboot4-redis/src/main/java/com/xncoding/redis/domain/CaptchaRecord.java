package com.xncoding.redis.domain;

import java.time.Instant;

/**
 * 存进 Redis 的验证码记录（JSON 序列化后的 value）。
 * <p>
 * 故意用普通类而不是 record：{@code GenericJacksonJsonRedisSerializer} 开启
 * 默认类型信息后，序列化时会把具体类型名写进 JSON（{@code @class} 字段），
 * 反序列化时靠它还原。record 是 final 类，走不了这条多态通道，
 * 读回来会退化成 Map —— 这是 Redis + JSON 序列化组合里最常见的一个坑，
 * README 和文章里都有展开。
 */
public class CaptchaRecord {

    /** 6 位数字验证码 */
    private String code;

    /** 已失败的校验次数 */
    private int attempts;

    /** 发送时间 */
    private Instant createdAt;

    public CaptchaRecord() {
    }

    public CaptchaRecord(String code, Instant createdAt) {
        this.code = code;
        this.createdAt = createdAt;
        this.attempts = 0;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
