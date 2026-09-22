package com.xncoding.aop.store;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 内存态订单与库存。教程场景用内存即可：本篇的重点在切面，不在存储。
 */
@Component
public class OrderStore {

    /** 初始库存，verify 脚本按这个数构造库存不足场景 */
    public static final int INITIAL_STOCK = 50;

    private final AtomicInteger stock = new AtomicInteger(INITIAL_STOCK);
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public record Order(String orderNo, BigDecimal amount, int itemCount,
                        String status, long placedAtMs) {
    }

    /** 扣库存：CAS 循环保证并发正确，不够扣返回 false，由服务层翻译成业务异常 */
    public boolean tryDeduct(int itemCount) {
        while (true) {
            int cur = stock.get();
            if (cur < itemCount) {
                return false;
            }
            if (stock.compareAndSet(cur, cur - itemCount)) {
                return true;
            }
        }
    }

    public int stockLeft() {
        return stock.get();
    }

    public void save(Order order) {
        orders.put(order.orderNo(), order);
    }

    public Optional<Order> find(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    public Order markPaid(Order order) {
        Order paid = new Order(order.orderNo(), order.amount(), order.itemCount(),
                "PAID", order.placedAtMs());
        orders.put(paid.orderNo(), paid);
        return paid;
    }

    public int orderCount() {
        return orders.size();
    }
}
