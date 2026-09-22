package com.xncoding.mongo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 订单明细入参（内嵌文档）。
 *
 * @param price    商品单价
 * @param quantity 数量
 */
public record OrderItemRequest(

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 64, message = "商品名称长度不能超过 64")
        String productName,

        @NotNull(message = "商品单价不能为空")
        @DecimalMin(value = "0.01", message = "商品单价必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "商品单价最多 10 位整数、2 位小数")
        BigDecimal price,

        @Min(value = 1, message = "商品数量不能小于 1")
        int quantity
) {
}
