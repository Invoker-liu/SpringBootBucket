package com.xncoding.observability.controller;

import com.xncoding.observability.domain.Order;
import com.xncoding.observability.metrics.OtlpLogBridge;
import com.xncoding.observability.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 订单 REST 接口：常规 / 慢 / 会抛异常三类路径，分别对应可观测性的三种典型场景。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orders;
    private final OtlpLogBridge otlpLogs;

    public OrderController(OrderService orders, OtlpLogBridge otlpLogs) {
        this.orders = orders;
        this.otlpLogs = otlpLogs;
    }

    public record CreateRequest(String customer, BigDecimal amount) {
    }

    @PostMapping
    public Order create(@RequestBody CreateRequest request) {
        Order order = orders.create(request.customer(), request.amount());
        log.info("订单已创建 id={} customer={}", order.id(), order.customer());
        otlpLogs.emitOrderPlaced(order.id(), order.customer());
        return order;
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable long id) {
        return orders.get(id);
    }

    @GetMapping("/{id}/process")
    public Order process(@PathVariable long id) {
        log.info("开始处理订单 id={}", id);
        return orders.process(id);
    }

    @GetMapping("/{id}/slow")
    public Order slow(@PathVariable long id) {
        log.info("慢请求进入 id={}", id);
        return orders.slow(id);
    }

    @GetMapping("/{id}/fail")
    public Order fail(@PathVariable long id) {
        log.warn("即将抛出业务异常 id={}", id);
        return orders.fail(id);
    }
}
