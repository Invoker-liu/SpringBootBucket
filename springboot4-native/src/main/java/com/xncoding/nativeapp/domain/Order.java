package com.xncoding.nativeapp.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单模型，纯查询用 record。
 */
public record Order(String id, String customer, String item, BigDecimal amount, Instant createdAt) {
}
