package com.xncoding.apiversion.controller;

import com.xncoding.apiversion.domain.Order;
import com.xncoding.apiversion.dto.OrderResponseV1;
import com.xncoding.apiversion.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v1 控制器：完整订单快照。
 * 版本声明在映射注解的 version 属性上，路径与 v2 完全相同。
 */
@RestController
@RequestMapping(value = "/api/orders", version = "1")
public class OrderControllerV1 {

    private final OrderService service;

    public OrderControllerV1(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrderResponseV1> list() {
        return service.findAll().stream().map(OrderControllerV1::toDto).toList();
    }

    @GetMapping("/{id}")
    public OrderResponseV1 detail(@PathVariable Long id) {
        return toDto(service.getById(id));
    }

    static OrderResponseV1 toDto(Order order) {
        return OrderResponseV1.of(order.getId(), order.getOrderNo(),
                order.getCustomerName(), order.getCustomerPhone(),
                order.getUnitPrice(), order.getQuantity(), order.getDiscount(),
                order.getShippingFee(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
