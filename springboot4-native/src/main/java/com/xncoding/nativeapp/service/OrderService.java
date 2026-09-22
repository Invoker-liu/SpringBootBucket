package com.xncoding.nativeapp.service;

import com.xncoding.nativeapp.config.ShopProperties;
import com.xncoding.nativeapp.domain.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存订单服务：种子数据常驻，供三个查询接口使用。
 */
@Service
public class OrderService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final ShopProperties shopProperties;

    public OrderService(ShopProperties shopProperties) {
        this.shopProperties = shopProperties;
        orders.put("order-1", new Order("order-1", "alice", "机械键盘",
                new BigDecimal("699.00"), Instant.parse("2026-09-18T02:15:00Z")));
        orders.put("order-2", new Order("order-2", "bob", "显示器支架",
                new BigDecimal("159.00"), Instant.parse("2026-09-19T08:30:00Z")));
        orders.put("order-3", new Order("order-3", "carol", "笔记本电脑",
                new BigDecimal("12999.00"), Instant.parse("2026-09-19T14:05:00Z")));
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> findAll() {
        return List.copyOf(orders.values());
    }

    /**
     * 大额订单统计：阈值来自 ShopProperties，
     * 验证 AOT 模式下 @ConfigurationProperties 反射绑定结果与普通模式一致。
     */
    public String bulkStats() {
        int threshold = shopProperties.getBulkThreshold();
        long count = orders.values().stream()
                .filter(o -> o.amount().doubleValue() >= threshold)
                .count();
        return "shop=" + shopProperties.getName()
                + ", vipDiscount=" + shopProperties.getVipDiscount()
                + ", bulkThreshold=" + threshold
                + ", bulkOrders=" + count;
    }
}
