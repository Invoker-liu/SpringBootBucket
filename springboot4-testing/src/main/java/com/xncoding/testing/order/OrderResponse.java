package com.xncoding.testing.order;

import java.time.Instant;

public record OrderResponse(Long id, String orderNo, java.math.BigDecimal amount, OrderStatus status, Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.orderNo(), order.amount(), order.status(), order.createdAt());
    }
}
