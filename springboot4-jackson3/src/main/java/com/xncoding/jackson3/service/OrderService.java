package com.xncoding.jackson3.service;

import com.xncoding.jackson3.domain.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OrderService {

    private final Map<Long, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(2);

    public OrderService() {
        // 订单 1：全字段非空（散客单带备注与优惠券）
        store.put(1L, new Order(1L, "SO-2026-0001", "张三", "13800138000",
                "SUMMER10", "尽快发货", 2, 24182L, 2000L, "CREATED",
                LocalDateTime.of(2026, 9, 20, 10, 30, 0)));
        // 订单 2：四个字段为 null（游客单，没留电话、没用券、没备注、没折扣）
        store.put(2L, new Order(2L, "SO-2026-0002", "李四", null,
                null, null, 1, 12990L, null, "CREATED",
                LocalDateTime.of(2026, 9, 20, 11, 5, 0)));
    }

    public Order getById(Long id) {
        Order order = store.get(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + id);
        }
        return order;
    }

    public List<Order> findAll() {
        return List.copyOf(store.values());
    }

    public Order create(String customerName, String merchantOrderNo, String couponCode,
                        int quantity, long amountCents, Long discountCents) {
        Order order = new Order(seq.incrementAndGet(),
                merchantOrderNo == null || merchantOrderNo.isBlank()
                        ? "SO-2026-%04d".formatted(seq.get()) : merchantOrderNo,
                customerName, null, couponCode, null, quantity, amountCents,
                discountCents, "CREATED", LocalDateTime.now());
        store.put(order.getId(), order);
        return order;
    }

    /** 分转元的展示口径，与序列化器保持同一个换算规则。 */
    public static BigDecimal centsToYuan(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** 元转分的解析口径，与反序列化器保持同一个换算规则。 */
    public static long yuanToCents(BigDecimal yuan) {
        return yuan.movePointRight(2).longValueExact();
    }
}
