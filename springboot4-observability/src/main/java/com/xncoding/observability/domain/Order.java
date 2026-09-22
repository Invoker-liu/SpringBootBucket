package com.xncoding.observability.domain;

import java.math.BigDecimal;

/**
 * 订单快照：演示可观测性的最小领域模型。
 */
public record Order(long id, String customer, BigDecimal amount, String status) {
}
