package com.xncoding.async.controller;

import com.xncoding.async.exception.BusinessException;
import com.xncoding.async.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下单接口：/api/orders 异步并行三件慢事，/api/orders/sync 同步串行做基线。
 */
@RestController
public class OrderController {

    public record CreateOrderRequest(
            @NotBlank @Pattern(regexp = "SO\\d{5}", message = "订单号格式为 SO + 5 位数字") String orderNo,
            @NotNull @DecimalMin(value = "0.01", message = "金额必须大于 0") BigDecimal amount) {
    }

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderService.PlaceResult result = orderService.placeOrder(request.orderNo(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(body(request.orderNo(), "async", result));
    }

    @PostMapping("/api/orders/sync")
    public ResponseEntity<Map<String, Object>> createOrderSync(@Valid @RequestBody CreateOrderRequest request) {
        OrderService.PlaceResult result = orderService.placeOrderSync(request.orderNo(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(body(request.orderNo(), "sync", result));
    }

    private Map<String, Object> body(String orderNo, String mode, OrderService.PlaceResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderNo", orderNo);
        map.put("taskId", result.taskId());
        map.put("mode", mode);
        map.put("elapsedMillis", result.elapsedMillis());
        return map;
    }

    @GetMapping("/api/orders/{orderNo}")
    public Map<String, Object> getOrder(@PathVariable String orderNo) {
        return orderService.findByOrderNo(orderNo);
    }
}
