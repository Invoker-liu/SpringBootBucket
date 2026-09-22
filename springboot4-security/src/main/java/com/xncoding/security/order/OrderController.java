package com.xncoding.security.order;

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
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public record CreateOrderRequest(String orderNo, BigDecimal amount) {
    }

    /** 订单列表：OPERATOR 与 ADMIN 都能看（路径级规则） */
    @GetMapping
    public List<Order> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody CreateOrderRequest req) {
        Order order = service.create(req.orderNo(), req.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/orders/" + order.orderNo())
                .body(order);
    }

    /** 取消订单：路径级放 OPERATOR，ADMIN 收敛在 OrderService.cancel 的 @PreAuthorize 上 */
    @PostMapping("/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo) {
        Order order = service.cancel(orderNo);
        return Map.of(
                "orderNo", order.orderNo(),
                "status", order.status(),
                "cancelledBy", OrderService.currentUser());
    }
}
