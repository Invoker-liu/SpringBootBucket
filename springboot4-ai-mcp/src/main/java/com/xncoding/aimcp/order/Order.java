package com.xncoding.aimcp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体。amount 用 BigDecimal 避免金额精度问题；
 * createdAt 用于「近 7 天订单汇总」工具的时间窗口判断。
 */
public record Order(
        String id,
        String customerId,
        String product,
        BigDecimal amount,
        String status,
        LocalDateTime createdAt
) {
}
