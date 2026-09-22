package com.xncoding.schedule.controller;

import com.xncoding.schedule.exception.BusinessException;
import com.xncoding.schedule.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单接口：给超时取消任务提供数据源。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(
            @NotBlank @Pattern(regexp = "SO\\d{4,}", message = "订单号格式为 SO 加 4 位以上数字")
            String orderNo,
            @DecimalMin(value = "0.01", message = "金额必须大于 0") String amount) {
    }

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrderRequest request) {
        Map<String, Object> order = orderService.create(request.orderNo(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> get(@PathVariable String orderNo) {
        return orderService.findByNo(orderNo);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return orderService.listAll();
    }
}
