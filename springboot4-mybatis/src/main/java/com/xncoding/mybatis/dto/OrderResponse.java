package com.xncoding.mybatis.dto;

import com.xncoding.mybatis.domain.Order;
import com.xncoding.mybatis.domain.OrderItem;
import com.xncoding.mybatis.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单响应体。
 * <p>
 * 把实体转成这一层再做两件事：手机号脱敏、补一个展示用的中文状态名。
 * 实体本身不会被序列化出去，{@code deleted} 这类内部字段自然也不会泄漏。
 *
 * @param version 乐观锁版本号。客户端做"读-改-写"时要把它一起带回来。
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
        List<OrderItem> items,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getCustomerName(),
                maskPhone(order.getCustomerPhone()),
                order.getTotalAmount(),
                order.getStatus(),
                order.getStatus().getLabel(),
                order.getRemark(),
                order.getItems(),
                order.getVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    /** 13800138000 -> 138****8000 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
