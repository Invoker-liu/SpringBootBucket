package com.xncoding.amqp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建订单请求。
 */
public record CreateOrderRequest(
        @NotBlank(message = "商品名不能为空") @Size(max = 64, message = "商品名最长 64 字符") String product,
        @NotBlank(message = "收件人不能为空") @Size(max = 32, message = "收件人最长 32 字符") String receiver) {
}
