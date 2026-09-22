package com.xncoding.cache.service;

import com.xncoding.cache.domain.Order;
import com.xncoding.cache.exception.BusinessException;
import com.xncoding.cache.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单服务：用内存 Map 模拟数据库，用 {@code dbHits} 计数器记录真实查库次数，
 * 让"缓存命中"从口号变成可以断言的数字。
 * <p>
 * 缓存名抽成 public 常量：CacheConfig 的 builder 定制、测试断言都要引用同名串。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 正式订单缓存：JSON 序列化，TTL 10 分钟 */
    public static final String CACHE_ORDERS = "orders";
    /** 订单快照缓存：TTL 2 秒，演示"不同缓存名不同过期时间" */
    public static final String CACHE_ORDER_FLASH = "order-flash";

    private final Map<String, Order> db = new ConcurrentHashMap<>();
    private final AtomicInteger dbHits = new AtomicInteger();
    private final AtomicLong orderSeq = new AtomicLong();

    /**
     * 查询订单。第一次调用走方法体（慢查询 + dbHits+1），结果进缓存；
     * 第二次相同 orderNo 直接从 Redis 拿，方法体根本不执行——dbHits 不再增长。
     * <p>
     * {@code sync = true}：同一 key 并发未命中时只放一个请求进方法体，
     * 其余线程等结果（防缓存击穿的最低成本写法）。
     */
    @Cacheable(cacheNames = CACHE_ORDERS, key = "#orderNo", sync = true)
    public Order getOrder(String orderNo) {
        dbHits.incrementAndGet();
        simulateSlowQuery();
        Order order = db.get(orderNo);
        if (order == null) {
            throw new ResourceNotFoundException("订单", orderNo, "订单不存在: " + orderNo);
        }
        log.info("[DB] 真实查询订单 {}", orderNo);
        return order;
    }

    /**
     * 快照查询：与 {@link #getOrder} 同样的数据，但落在 2 秒 TTL 的 order-flash 缓存。
     * 演示场景是"列表页顶部的实时库存角标"这类短命数据。
     */
    @Cacheable(cacheNames = CACHE_ORDER_FLASH, key = "#orderNo", sync = true)
    public Order getOrderFlash(String orderNo) {
        dbHits.incrementAndGet();
        simulateSlowQuery();
        Order order = db.get(orderNo);
        if (order == null) {
            throw new ResourceNotFoundException("订单", orderNo, "订单不存在: " + orderNo);
        }
        log.info("[DB] 真实查询订单快照 {}", orderNo);
        return order;
    }

    /**
     * 支付。写库后用 {@code @CachePut} 把新状态同步进缓存——不是删掉重查，
     * 而是直接替换，下一步读到的就是 PAID 且 dbHits 不涨。
     * <p>
     * 方法返回值会整个写入缓存，所以方法必须返回更新后的完整对象；
     * 返回 void 或返回 null 等于往缓存里塞 null。
     */
    @CachePut(cacheNames = CACHE_ORDERS, key = "#orderNo")
    public Order pay(String orderNo) {
        Order order = mustGet(orderNo);
        if (!Order.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "只有待支付订单可以支付，当前状态: " + order.getStatus());
        }
        order.setStatus(Order.STATUS_PAID);
        order.setPayTime(Instant.now());
        simulateSlowQuery();
        log.info("[DB] 订单 {} 已支付", orderNo);
        return order;
    }

    /**
     * 取消。{@code @CacheEvict} 把这个 key 从缓存里删掉：下次查询必然重新走库。
     * 状态流转本身在库里完成，缓存只负责"忘掉"。
     */
    @CacheEvict(cacheNames = CACHE_ORDERS, key = "#orderNo")
    public Order cancel(String orderNo) {
        Order order = mustGet(orderNo);
        if (!Order.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "只有待支付订单可以取消，当前状态: " + order.getStatus());
        }
        order.setStatus(Order.STATUS_CANCELLED);
        simulateSlowQuery();
        log.info("[DB] 订单 {} 已取消", orderNo);
        return order;
    }

    /**
     * 清空整个 orders 缓存（{@code allEntries = true}）。
     * 演示场景：商品价格批量调整后，与其逐个驱逐不如整块清空。
     */
    @CacheEvict(cacheNames = CACHE_ORDERS, allEntries = true)
    public void evictAllOrders() {
        log.info("[CACHE] 手动清空 {} 缓存全部条目", CACHE_ORDERS);
    }

    /** 创建订单：直接写库，不进缓存（查询时才首次加载） */
    public Order create(String product, BigDecimal amount) {
        String orderNo = "SO" + String.format("%011d", orderSeq.incrementAndGet());
        Order order = new Order(orderNo, product, amount);
        db.put(orderNo, order);
        return order;
    }

    public long dbHits() {
        return dbHits.get();
    }

    public long orderCount() {
        return db.size();
    }

    private Order mustGet(String orderNo) {
        Order order = db.get(orderNo);
        if (order == null) {
            throw new ResourceNotFoundException("订单", orderNo, "订单不存在: " + orderNo);
        }
        return order;
    }

    /** 模拟一次 300ms 的慢查询：没命中缓存的调用可观测地变慢 */
    private void simulateSlowQuery() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
