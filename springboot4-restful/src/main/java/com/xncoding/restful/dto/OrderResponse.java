package com.xncoding.restful.dto;

import com.xncoding.restful.domain.Order;
import com.xncoding.restful.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单响应体。
 * <p>
 * 响应体是接口契约的一部分，独立于领域模型演进：
 * 以后领域模型新增内部字段（如 version、deleted），不会被动泄漏给客户端。
 *
 * @param id             主键
 * @param orderNo        订单号
 * @param customerName   客户姓名
 * @param customerPhone  客户手机号（已脱敏）
 * @param totalAmount    订单金额
 * @param status         状态枚举名
 * @param statusLabel    状态中文描述
 * @param remark         备注
 * @param createdAt      创建时间
 * @param updatedAt      最后更新时间
 */
public record OrderResponse(
        Long id,
        String orderNo,
        String customerName,
        String customerPhone,
        BigDecimal totalAmount,
        OrderStatus status,
        String statusLabel,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 由领域模型转换为响应体。
     * <p>
     * 手机号按 138****8000 脱敏，避免响应体与日志中落明文。
     */
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(),
                order.orderNo(),
                order.customerName(),
                maskPhone(order.customerPhone()),
                order.totalAmount(),
                order.status(),
                order.status().getLabel(),
                order.remark(),
                order.createdAt(),
                order.updatedAt()
        );
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
