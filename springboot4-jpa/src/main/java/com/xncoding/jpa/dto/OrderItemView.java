package com.xncoding.jpa.dto;

import com.xncoding.jpa.domain.OrderItem;

import java.math.BigDecimal;

/**
 * 订单明细响应体。
 * <p>
 * 只带 {@code id} 和业务字段，不带 {@code order}——那是内部关联，
 * 暴露出去既泄漏结构，也会让序列化层层往外爬。
 */
public record OrderItemView(
        Long id,
        String productName,
        BigDecimal price,
        Integer quantity,
        BigDecimal amount
) {

    public static OrderItemView from(OrderItem item) {
        return new OrderItemView(
                item.getId(),
                item.getProductName(),
                item.getPrice(),
                item.getQuantity(),
                item.getAmount());
    }
}
