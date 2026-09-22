package com.xncoding.observability.service;

import com.xncoding.observability.domain.Order;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单服务：内存存储 + 三个观测切面。
 * Counter 记录订单创建次数，Timer 记录处理耗时，Observation 同时产出子 Span 与耗时指标。
 */
@Service
public class OrderService {

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NoSuchOrderException extends RuntimeException {
        public NoSuchOrderException(long id) {
            super("订单不存在: " + id);
        }
    }

    private final Map<Long, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private final Counter createdCounter;
    private final Timer processingTimer;
    private final ObservationRegistry observations;

    public OrderService(MeterRegistry registry, ObservationRegistry observations) {
        this.observations = observations;
        // 注意：计数器名不能以 created 结尾，Prometheus 命名约定会把 _created
        // 当作 OpenMetrics 的时间戳后缀吞掉，orders.created 实际导出成 orders_total
        this.createdCounter = Counter.builder("orders.placed")
                .description("累计创建的订单数")
                .tag("channel", "web")
                .register(registry);
        this.processingTimer = Timer.builder("orders.processing")
                .description("订单处理耗时")
                .tag("outcome", "ok")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    public Order create(String customer, BigDecimal amount) {
        Order order = new Order(seq.incrementAndGet(), customer, amount, "CREATED");
        store.put(order.id(), order);
        createdCounter.increment();
        return order;
    }

    public Order get(long id) {
        Order order = store.get(id);
        if (order == null) {
            throw new NoSuchOrderException(id);
        }
        return order;
    }

    /** 正常处理路径：Timer 计时 + Observation 产出子 Span（带 traceId 贯穿）。 */
    public Order process(long id) {
        get(id);
        return processingTimer.record(() ->
                Observation.createNotStarted("orders.process", observations)
                        .lowCardinalityKeyValue("order.stage", "process")
                        .observe(() -> {
                            sleep(300);
                            return markPaid(id);
                        }));
    }

    /** 慢接口：固定 800ms 延迟，供 p95 与 http.server.requests 慢请求观测。 */
    public Order slow(long id) {
        get(id);
        sleep(800);
        return get(id);
    }

    /** 故意抛出的业务异常：用于验证失败路径的指标计数与链路上的异常 Span。 */
    public Order fail(long id) {
        get(id);
        throw new IllegalStateException("模拟支付网关不可用（故意抛出的业务异常）");
    }

    private Order markPaid(long id) {
        Order order = store.get(id);
        Order updated = new Order(order.id(), order.customer(), order.amount(), "PAID");
        store.put(id, updated);
        return updated;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
