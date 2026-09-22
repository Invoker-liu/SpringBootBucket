package com.xncoding.openapi.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final Map<String, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public Order create(String orderNo, BigDecimal amount, String note, String createdBy) {
        Order order = new Order(seq.incrementAndGet(), orderNo, amount,
                OrderStatus.NEW, note, Instant.now(), createdBy);
        Order prev = store.putIfAbsent(orderNo, order);
        if (prev != null) {
            throw new DuplicateOrderException(orderNo);
        }
        return order;
    }

    public List<Order> list(OrderStatus status) {
        List<Order> all = new ArrayList<>(store.values());
        return status == null ? all : all.stream().filter(o -> o.status() == status).toList();
    }

    public Optional<Order> byId(Long id) {
        return store.values().stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public boolean delete(Long id) {
        Optional<Order> found = byId(id);
        return found.filter(o -> store.remove(o.orderNo()) != null).isPresent();
    }

    public int count() {
        return store.size();
    }
}
