package com.xncoding.openapi.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单实体（内存存储，延续系列订单业务场景）。
 * 面向前端与第三方的出参一律走 OrderResponse，实体不直接出现在文档里。
 */
public record Order(
        Long id,
        String orderNo,
        BigDecimal amount,
        OrderStatus status,
        String note,
        Instant createdAt,
        String createdBy) {
}
