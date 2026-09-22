package com.xncoding.amqp.controller;

import com.xncoding.amqp.dto.CreateOrderRequest;
import com.xncoding.amqp.dto.OrderView;
import com.xncoding.amqp.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单 API：下单/支付各自触发一条事件发布，链路的起点。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request.product(), request.receiver());
    }

    @PostMapping("/{orderNo}/pay")
    public OrderView pay(@PathVariable String orderNo) {
        return orderService.pay(orderNo);
    }

    @GetMapping("/{orderNo}")
    public OrderView get(@PathVariable String orderNo) {
        return orderService.find(orderNo);
    }

    @GetMapping
    public List<OrderView> all() {
        return orderService.allOrders();
    }
}
