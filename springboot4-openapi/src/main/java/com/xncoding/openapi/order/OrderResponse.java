package com.xncoding.openapi.order;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 订单出参 DTO。字段命名保持驼峰原样进入 schema，
 * status 是 OrderStatus 枚举，schema 里表现为 enum 数组。
 */
@Schema(description = "订单信息")
public record OrderResponse(

        @Schema(description = "订单主键", example = "1001")
        Long id,

        @Schema(description = "业务单号，全局唯一", example = "SO-2026-0001")
        String orderNo,

        @Schema(description = "订单金额，单位元", example = "359.00")
        BigDecimal amount,

        @Schema(description = "订单状态", implementation = OrderStatus.class)
        OrderStatus status,

        @Schema(description = "备注，可空", nullable = true)
        String note,

        @Schema(description = "创建时间，UTC", example = "2026-09-20T04:30:00Z")
        Instant createdAt,

        @Schema(description = "创建人（Basic 认证用户名或 system）", example = "admin")
        String createdBy) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.orderNo(), order.amount(),
                order.status(), order.note(), order.createdAt(), order.createdBy());
    }
}
