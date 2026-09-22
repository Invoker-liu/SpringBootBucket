package com.xncoding.redis.dto;

/**
 * 发送成功响应：有效期与重发冷却秒数。
 */
public record CaptchaSendResponse(
        String email,
        long expiresInSecond,
        long resendAfterSecond) {
}
