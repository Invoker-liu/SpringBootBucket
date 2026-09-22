package com.xncoding.grpc.web;

import com.xncoding.grpc.client.LogisticsClient;
import com.xncoding.grpc.client.LogisticsClient.ShipmentView;
import com.xncoding.grpc.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** 订单 HTTP 面：下单与详情，内部经 gRPC 调物流 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final LogisticsClient logistics;

    public OrderController(OrderService orderService, LogisticsClient logistics) {
        this.orderService = orderService;
        this.logistics = logistics;
    }

    public record PlaceOrderRequest(String orderNo, BigDecimal amount, int itemCount) {
    }

    @PostMapping
    public OrderService.Order place(@RequestBody PlaceOrderRequest request) {
        return orderService.placeOrder(request.orderNo(), request.amount(), request.itemCount());
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        OrderService.Order order = orderService.find(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        // 经 gRPC 刷新运单状态（unary）
        Optional<ShipmentView> fresh = order.shipment() == null
                ? Optional.empty()
                : logistics.getShipment(order.shipment().shipmentId());
        return Map.of(
                "orderNo", order.orderNo(),
                "amount", order.amount(),
                "status", order.status(),
                "shipment", fresh.<Object>map(s -> Map.of(
                        "shipmentId", s.shipmentId(),
                        "carrier", s.carrier(),
                        "status", s.status())).orElse("none"));
    }
}
