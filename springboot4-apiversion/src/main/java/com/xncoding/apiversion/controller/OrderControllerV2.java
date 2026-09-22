package com.xncoding.apiversion.controller;

import com.xncoding.apiversion.domain.Order;
import com.xncoding.apiversion.dto.OrderResponseV2;
import com.xncoding.apiversion.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v2 控制器：拆字段 + 金额明细。与 v1 同路径、同一个 service，只有 DTO 与 version 不同。
 */
@RestController
@RequestMapping(value = "/api/orders", version = "2")
public class OrderControllerV2 {

    private final OrderService service;

    public OrderControllerV2(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrderResponseV2> list() {
        return service.findAll().stream().map(OrderControllerV2::toDto).toList();
    }

    @GetMapping("/{id}")
    public OrderResponseV2 detail(@PathVariable Long id) {
        return toDto(service.getById(id));
    }

    static OrderResponseV2 toDto(Order order) {
        return OrderResponseV2.of(order.getId(), order.getOrderNo(),
                order.getCustomerName(), order.getCustomerPhone(),
                order.getUnitPrice(), order.getQuantity(), order.getDiscount(),
                order.getShippingFee(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
