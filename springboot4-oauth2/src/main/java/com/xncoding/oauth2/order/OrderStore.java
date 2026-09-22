package com.xncoding.oauth2.order;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderStore {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public Order create(String orderNo, BigDecimal amount, String createdBy) {
        Order order = new Order(orderNo, amount, Order.STATUS_NEW, createdBy, Instant.now());
        Order prev = orders.putIfAbsent(orderNo, order);
        if (prev != null) {
            throw new DuplicateOrderException(orderNo);
        }
        return order;
    }

    public List<Order> all() {
        return List.copyOf(orders.values());
    }

    public Order get(String orderNo) {
        Order order = orders.get(orderNo);
        if (order == null) {
            throw new OrderNotFoundException(orderNo);
        }
        return order;
    }

    public void update(Order order) {
        orders.put(order.orderNo(), order);
    }
}
