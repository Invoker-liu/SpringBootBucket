package com.xncoding.jackson3.web.dto;

import com.xncoding.jackson3.domain.Order;

import java.time.LocalDateTime;

/**
 * record 版响应 DTO，实测 Jackson 3 对 record 组件的输出行为。
 */
public record OrderRecordResponse(
        Long id,
        String orderNo,
        String customerName,
        String customerPhone,
        String couponCode,
        int quantity,
        long amountCents,
        Long discountCents,
        String status,
        LocalDateTime createdAt) {

    public static OrderRecordResponse of(Order o) {
        return new OrderRecordResponse(o.getId(), o.getOrderNo(), o.getCustomerName(),
                o.getCustomerPhone(), o.getCouponCode(), o.getQuantity(),
                o.getAmountCents(), o.getDiscountCents(), o.getStatus(), o.getCreatedAt());
    }
}
