package com.xncoding.testing.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public Order create(String orderNo, BigDecimal amount) {
        if (orders.existsByOrderNo(orderNo)) {
            throw new DuplicateOrderException(orderNo);
        }
        return orders.insert(new Order(null, orderNo, amount, OrderStatus.NEW, Instant.now()));
    }

    public Order getById(Long id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> list(OrderStatus status) {
        return orders.findAll(status);
    }

    public Order pay(Long id) {
        Order order = getById(id);
        if (order.status() != OrderStatus.NEW) {
            throw new IllegalStateException("仅 NEW 状态可支付，当前: " + order.status());
        }
        orders.updateStatus(id, OrderStatus.PAID);
        return order.paid();
    }

    public boolean delete(Long id) {
        return orders.deleteById(id);
    }
}
