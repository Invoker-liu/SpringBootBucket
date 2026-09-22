package com.xncoding.oauth2.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(String orderNo, BigDecimal amount) {
    }

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Order> list() {
        return service.list();
    }

    @GetMapping("/{orderNo}")
    public Order get(@PathVariable String orderNo) {
        return service.get(orderNo);
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody CreateOrderRequest req) {
        Order order = service.create(req.orderNo(), req.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PostMapping("/{orderNo}/cancel")
    public Order cancel(@PathVariable String orderNo) {
        return service.cancel(orderNo);
    }
}
