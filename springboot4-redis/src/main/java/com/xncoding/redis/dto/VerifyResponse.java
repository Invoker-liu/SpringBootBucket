package com.xncoding.redis.dto;

/**
 * 校验结果响应。
 */
public record VerifyResponse(
        String email,
        boolean verified) {
}
