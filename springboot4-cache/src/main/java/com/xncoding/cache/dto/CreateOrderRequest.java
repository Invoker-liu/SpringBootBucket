package com.xncoding.cache.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 创建订单请求体。
 */
public record CreateOrderRequest(
        @NotBlank(message = "商品名不能为空") String product,
        @NotNull(message = "金额不能为空") @DecimalMin(value = "0.01", message = "金额必须大于 0") BigDecimal amount) {
}
