package com.xncoding.redis.dto;

import java.time.Instant;

/**
 * 验证码状态查询响应：剩余有效期与剩余尝试次数。
 */
public record CaptchaStatusResponse(
        String email,
        long remainingSecond,
        int remainingAttempts,
        Instant createdAt) {
}
