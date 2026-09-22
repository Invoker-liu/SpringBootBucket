package com.xncoding.mongo.dto;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单响应体。
 * <p>
 * 把文档转成这一层做三件事：手机号脱敏、补一个展示用的中文状态名、
 * 把内嵌明细转成不带 {@code _id} 的视图。
 * <p>
 * <b>主键类型在这里是 {@code String}</b>——前三篇都是 {@code Long}。
 * 这是唯一一处接口"长得不完全一样"的地方，因为 MongoDB 的 {@code _id} 本来就不是整数。
 * {@code /api/orders/6742a1f3c9e77b1a2b3c4d5e} 这样的路径是正常的。
 *
 * @param version 乐观锁版本号。客户端做"读-改-写"时要把它一起带回来。
 */
public record OrderResponse(
        String id,
        String orderNo,
        String customerName,
        String customerPhone,
        BigDecimal totalAmount,
        OrderStatus status,
        String statusLabel,
        String remark,
        List<OrderItemView> items,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 由文档转换。
     * <p>
     * 和 JPA 那篇不同，这里<b>不需要</b>在事务里调用。内嵌数组是文档的一部分，
     * 从 MongoDB 读出来时明细已经在内存里了，不存在懒加载、也不会出现
     * {@code LazyInitializationException}。这也是文档型存储少掉的一整类问题。
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
