package com.xncoding.mongo.dto;

import com.xncoding.mongo.domain.OrderItem;

import java.math.BigDecimal;

/**
 * 订单明细视图。
 * <p>
 * 单独一层是为了把 {@code amount} 这个计算字段带出去——它标了 {@code @Transient}，
 * 不在文档里，只能在转换成视图时现算。
 */
public record OrderItemView(
        String productName,
        BigDecimal price,
        int quantity,
        BigDecimal amount
) {

    public static OrderItemView from(OrderItem item) {
        return new OrderItemView(
                item.getProductName(),
                item.getPrice(),
                item.getQuantity(),
                item.getAmount());
    }
}
