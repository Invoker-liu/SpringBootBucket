package com.xncoding.grpc.web;

import com.xncoding.grpc.client.LogisticsClient;
import com.xncoding.grpc.client.LogisticsClient.ShipmentView;
import com.xncoding.grpc.client.LogisticsClient.TrackEventView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 运单 HTTP 面：查询走 unary，dispatch 内部走 gRPC 通知 + 服务端流式轨迹拉取 */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final LogisticsClient logistics;

    public ShipmentController(LogisticsClient logistics) {
        this.logistics = logistics;
    }

    @GetMapping("/{shipmentId}")
    public ShipmentView get(@PathVariable String shipmentId) {
        return logistics.getShipment(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found"));
    }

    @PostMapping("/{shipmentId}/dispatch")
    public Map<String, Object> dispatch(@PathVariable String shipmentId) {
        ShipmentView shipment = logistics.getShipment(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found"));
        // 服务端流式：一次调用拉回完整轨迹
        List<TrackEventView> events = logistics.trackShipment(shipmentId);
        return Map.of(
                "shipment", shipment,
                "events", events,
                "eventCount", events.size());
    }
}
