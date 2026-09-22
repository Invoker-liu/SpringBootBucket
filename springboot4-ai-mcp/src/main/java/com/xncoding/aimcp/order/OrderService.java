package com.xncoding.aimcp.order;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务服务：内存实现，专注演示 MCP 工具层怎么包业务方法。
 * 真实项目里这里下面是 MyBatis/JPA，工具注解只加在这层方法之上。
 */
@Service
public class OrderService {

    private final Map<String, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public OrderService() {
        // 种子数据：两笔近期订单 + 一笔 10 天前的旧订单，
        // 让「近 7 天汇总」工具能区分时间窗口内外
        save(new Order(nextId(), "C-001", "机械键盘", new BigDecimal("399.00"), "PAID", LocalDateTime.now().minusDays(1)));
        save(new Order(nextId(), "C-001", "显示器", new BigDecimal("1299.00"), "PAID", LocalDateTime.now().minusDays(3)));
        save(new Order(nextId(), "C-002", "无线鼠标", new BigDecimal("159.00"), "SHIPPED", LocalDateTime.now().minusDays(10)));
    }

    public Optional<Order> getOrderById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Order> listOrdersByCustomer(String customerId) {
        return store.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .toList();
    }

    public Order createOrder(String customerId, String product, BigDecimal amount) {
        Order order = new Order(nextId(), customerId, product, amount, "CREATED", LocalDateTime.now());
        save(order);
        return order;
    }

    /** 近 7 天订单汇总：组合工具内部复用查询能力，返回结构化统计 */
    public WeeklySummary weeklySummary() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<Order> recent = store.values().stream()
                .filter(o -> o.createdAt().isAfter(since))
                .toList();
        BigDecimal total = recent.stream()
                .map(Order::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WeeklySummary(recent.size(), total, store.size());
    }

    public record WeeklySummary(int recentCount, BigDecimal recentAmount, int totalCount) {
    }

    private String nextId() {
        return "ORD-" + seq.incrementAndGet();
    }

    private void save(Order order) {
        store.put(order.id(), order);
    }
}
