package com.xncoding.grpc.service;

import com.xncoding.grpc.client.LogisticsClient;
import com.xncoding.grpc.client.LogisticsClient.ShipmentView;
import com.xncoding.grpc.metrics.CallMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 订单编排：下单即经 gRPC 通知物流，失败降级不阻断下单 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final LogisticsClient logistics;
    private final CallMetrics metrics;

    public OrderService(LogisticsClient logistics, CallMetrics metrics) {
        this.logistics = logistics;
        this.metrics = metrics;
    }

    public Order placeOrder(String orderNo, BigDecimal amount, int itemCount) {
        metrics.incOrdersPlaced();
        Optional<ShipmentView> shipment = logistics.notifyShipped(orderNo, "STO", itemCount);
        Order order = new Order(orderNo, amount, itemCount,
                shipment.orElse(null),
                shipment.isPresent() ? "OK" : "DEGRADE_DEADLINE",
                System.currentTimeMillis());
        orders.put(orderNo, order);
        log.info("ORDER_PLACED orderNo={} status={} shipment={}",
                orderNo, order.status(), order.shipment() == null ? "none" : order.shipment().shipmentId());
        return order;
    }

    public Optional<Order> find(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    public record Order(String orderNo, BigDecimal amount, int itemCount,
                        ShipmentView shipment, String status, long placedAtMs) {
    }
}
