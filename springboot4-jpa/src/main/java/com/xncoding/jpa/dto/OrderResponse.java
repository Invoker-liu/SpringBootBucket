package com.xncoding.jpa.dto;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单响应体。
 * <p>
 * 把实体转成这一层做三件事：手机号脱敏、补一个展示用的中文状态名、
 * 把明细转成不带反向引用的视图。
 * <p>
 * 实体本身不会被序列化出去，所以 {@code deleted}、{@code version} 这类字段
 * 会不会泄漏、懒加载集合会不会在序列化时炸掉，都控制在这一次转换里。
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
        List<OrderItemView> items,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 由实体转换。
     * <p>
     * <b>必须在一个打开的事务里调用</b>：{@code order.getItems()} 是懒加载集合，
     * 事务关掉之后再访问会抛 {@code LazyInitializationException}。
     * 本项目把 {@code spring.jpa.open-in-view} 关掉了，就是为了让这类
     * "不小心在视图层碰懒加载"的问题在开发阶段直接暴露，而不是上线后变成慢查询。
     */
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
                order.getItems().stream().map(OrderItemView::from).toList(),
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
