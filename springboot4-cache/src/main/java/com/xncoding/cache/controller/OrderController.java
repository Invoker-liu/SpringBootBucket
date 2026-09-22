package com.xncoding.cache.controller;

import com.xncoding.cache.domain.Order;
import com.xncoding.cache.dto.CreateOrderRequest;
import com.xncoding.cache.dto.OrderResponse;
import com.xncoding.cache.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * 订单接口。GET /orders/{orderNo} 的响应耗时是缓存效果的直观体现：
 * 未命中约 300ms，命中在 10ms 以内。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.create(request.product(), request.amount());
        OrderResponse body = toResponse(order);
        return ResponseEntity
                .created(URI.create("/api/orders/" + order.getOrderNo()))
                .body(body);
    }

    /** @Cacheable 的入口：第一次 ~300ms（真实查库），之后走缓存 */
    @GetMapping("/{orderNo}")
    public OrderResponse get(@PathVariable String orderNo) {
        return toResponse(orderService.getOrder(orderNo));
    }

    /** 快照入口：2 秒 TTL 的 order-flash 缓存 */
    @GetMapping("/{orderNo}/flash")
    public OrderResponse getFlash(@PathVariable String orderNo) {
        return toResponse(orderService.getOrderFlash(orderNo));
    }

    /** @CachePut 的入口：支付后缓存里的状态同步变成 PAID */
    @PostMapping("/{orderNo}/pay")
    public OrderResponse pay(@PathVariable String orderNo) {
        return toResponse(orderService.pay(orderNo));
    }

    /** @CacheEvict 的入口（单 key）：取消后缓存条目被删除 */
    @PostMapping("/{orderNo}/cancel")
    public OrderResponse cancel(@PathVariable String orderNo) {
        return toResponse(orderService.cancel(orderNo));
    }

    /** @CacheEvict(allEntries = true) 的入口 */
    @DeleteMapping("/cache")
    public Map<String, String> evictAll() {
        orderService.evictAllOrders();
        return Map.of("result", "orders 缓存已全部清空");
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getOrderNo(), order.getProduct(), order.getAmount(),
                order.getStatus(), order.getCreatedAt(), order.getPayTime());
    }
}
