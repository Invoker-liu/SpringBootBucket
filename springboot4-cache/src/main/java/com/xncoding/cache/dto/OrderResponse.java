package com.xncoding.cache.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单响应体。只出现在 HTTP 出参，不进缓存——缓存里存的是领域对象 {@code Order}。
 */
public record OrderResponse(
        String orderNo,
        String product,
        BigDecimal amount,
        String status,
        Instant createdAt,
        Instant payTime) {
}
