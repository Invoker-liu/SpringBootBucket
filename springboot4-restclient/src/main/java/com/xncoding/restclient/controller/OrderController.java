package com.xncoding.restclient.controller;

import com.xncoding.restclient.metrics.CallMetrics;
import com.xncoding.restclient.model.Waybill;
import com.xncoding.restclient.service.OrderService;
import com.xncoding.restclient.service.OrderService.PlacedOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CallMetrics metrics;

    public OrderController(OrderService orderService, CallMetrics metrics) {
        this.orderService = orderService;
        this.metrics = metrics;
    }

    /** 下单：内部先走风控客户端校验，再走物流客户端建运单 */
    @PostMapping
    public PlacedOrder place(@RequestBody Map<String, Object> req) {
        String orderNo = String.valueOf(req.get("orderNo"));
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "0")));
        int itemCount = Integer.parseInt(String.valueOf(req.getOrDefault("itemCount", "1")));
        return orderService.placeOrder(orderNo, amount, itemCount);
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String orderNo) {
        PlacedOrder order = orderService.get(orderNo);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        Waybill status = order.waybill() == null ? null
                : orderService.waybillStatus(order.waybill().waybillNo());
        return ResponseEntity.ok(Map.of(
                "order", order,
                "waybillStatus", status == null ? "NO_WAYBILL" : status.status()));
    }

    /** 调用计数台账：取值单数据源 */
    @GetMapping("/metrics")
    public CallMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
