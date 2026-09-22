package com.xncoding.apiversion.repository;

import com.xncoding.apiversion.domain.Order;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存订单仓库，两个版本的 controller 共用。
 */
@Repository
public class OrderRepository {

    private final Map<Long, Order> store = new ConcurrentHashMap<>();

    private final AtomicLong idGen = new AtomicLong(0);

    public OrderRepository() {
        // 预置一条演示数据，保证 GET 有真实响应
        save(new Order(null, "SO-2026-0001", "张三", "13800138000",
                new BigDecimal("129.90"), 2, new BigDecimal("0.90"),
                new BigDecimal("8.00"), "CREATED", LocalDateTime.of(2026, 9, 20, 10, 30, 0)));
    }

    public Order save(Order order) {
        if (order.getId() == null) {
            order.setId(idGen.incrementAndGet());
        }
        store.put(order.getId(), order);
        return order;
    }

    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Order> findAll() {
        return List.copyOf(store.values());
    }
}
