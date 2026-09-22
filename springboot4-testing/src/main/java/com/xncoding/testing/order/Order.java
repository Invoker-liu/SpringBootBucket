package com.xncoding.testing.order;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(Long id, String orderNo, BigDecimal amount, OrderStatus status, Instant createdAt) {

    public Order paid() {
        return new Order(id, orderNo, amount, OrderStatus.PAID, createdAt);
    }
}
