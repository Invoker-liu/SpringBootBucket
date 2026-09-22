package com.xncoding.security.order;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 订单内存仓库。ConcurrentHashMap 够演示用，鉴权与审计才是本篇的关注点。 */
@Component
public class OrderStore {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public Order create(String orderNo, BigDecimal amount, String createdBy) {
        Order order = new Order(orderNo, amount, Order.STATUS_CREATED, createdBy, Instant.now());
        Order prev = orders.putIfAbsent(orderNo, order);
        if (prev != null) {
            throw new DuplicateOrderException(orderNo);
        }
        return order;
    }

    public Order get(String orderNo) {
        Order order = orders.get(orderNo);
        if (order == null) {
            throw new OrderNotFoundException(orderNo);
        }
        return order;
    }

    public Order update(Order order) {
        orders.put(order.orderNo(), order);
        return order;
    }

    public int count() {
        return orders.size();
    }

    public java.util.List<Order> all() {
        return orders.values().stream()
                .sorted(Comparator.comparing(Order::createdAt))
                .toList();
    }
}
