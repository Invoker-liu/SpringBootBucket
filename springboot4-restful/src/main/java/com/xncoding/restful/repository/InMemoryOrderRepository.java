package com.xncoding.restful.repository;

import com.xncoding.restful.domain.Order;
import com.xncoding.restful.domain.OrderPageQuery;
import com.xncoding.restful.domain.PageSlice;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单仓储的内存实现。
 * <p>
 * 用 {@link ConcurrentHashMap} 保存整对象、{@link AtomicLong} 分配主键，
 * 更新即"整对象替换"，因此读操作天然拿到某个一致快照，不会读到半个订单。
 * 这里不引入数据库，是为了让 RESTful 主题能零外部依赖启动；
 * 数据在进程重启后清空，属预期行为。
 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<Long, Order> store = new ConcurrentHashMap<>();

    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public Order save(Order order) {
        Long id = order.id();
        if (id == null) {
            id = idGenerator.incrementAndGet();
            order = order.withId(id);
        }
        store.put(id, order);
        return order;
    }

    @Override
    public Optional<Order> findById(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        return store.values().stream()
                .filter(order -> order.orderNo().equals(orderNo))
                .findFirst();
    }

    @Override
    public boolean existsByOrderNo(String orderNo) {
        return store.values().stream().anyMatch(order -> order.orderNo().equals(orderNo));
    }

    @Override
    public PageSlice<Order> findPage(OrderPageQuery query) {
        List<Order> matched = store.values().stream()
                .filter(order -> matchesStatus(order, query))
                .filter(order -> matchesKeyword(order, query))
                .sorted(comparator(query))
                .toList();

        long total = matched.size();
        int from = Math.min(query.page() * query.size(), matched.size());
        int to = Math.min(from + query.size(), matched.size());
        return new PageSlice<>(List.copyOf(matched.subList(from, to)), total);
    }

    @Override
    public boolean deleteById(Long id) {
        return id != null && store.remove(id) != null;
    }

    private boolean matchesStatus(Order order, OrderPageQuery query) {
        return query.status() == null || order.status() == query.status();
    }

    private boolean matchesKeyword(Order order, OrderPageQuery query) {
        String keyword = query.keyword();
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return order.orderNo().toLowerCase(Locale.ROOT).contains(lower)
                || order.customerName().toLowerCase(Locale.ROOT).contains(lower);
    }

    private Comparator<Order> comparator(OrderPageQuery query) {
        Comparator<Order> comparator = switch (query.sortBy()) {
            case "id" -> Comparator.comparing(Order::id);
            case "orderNo" -> Comparator.comparing(Order::orderNo);
            case "customerName" -> Comparator.comparing(Order::customerName);
            case "totalAmount" -> Comparator.comparing(Order::totalAmount);
            case "status" -> Comparator.comparing(Order::status);
            case "updatedAt" -> Comparator.comparing(Order::updatedAt);
            default -> Comparator.comparing(Order::createdAt);
        };
        // 次排序键固定为主键，保证分页顺序稳定，翻页不会出现重复或漏项
        comparator = comparator.thenComparing(Order::id);
        return query.descending() ? comparator.reversed() : comparator;
    }

    /**
     * 仅供测试与调试使用的快照方法。
     */
    public Map<Long, Order> snapshot() {
        return new LinkedHashMap<>(store);
    }
}
