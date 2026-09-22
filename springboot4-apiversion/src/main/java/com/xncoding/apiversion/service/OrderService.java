package com.xncoding.apiversion.service;

import com.xncoding.apiversion.domain.Order;
import com.xncoding.apiversion.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单业务服务。v1 与 v2 的 controller 都调这一个类：
 * 版本分叉停在接口层，业务与数据层只有一份。
 */
@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public Order getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + id));
    }

    public List<Order> findAll() {
        return repository.findAll();
    }

    public Order create(String customerName, String customerPhone,
                        BigDecimal unitPrice, Integer quantity) {
        Order order = new Order(null, "SO-" + System.currentTimeMillis(),
                customerName, customerPhone, unitPrice, quantity,
                BigDecimal.ONE, BigDecimal.ZERO, "CREATED", LocalDateTime.now());
        return repository.save(order);
    }

    public long count() {
        return repository.findAll().size();
    }
}
