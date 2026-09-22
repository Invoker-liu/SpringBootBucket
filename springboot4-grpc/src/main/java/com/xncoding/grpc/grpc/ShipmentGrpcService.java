package com.xncoding.grpc.grpc;

import com.xncoding.grpc.config.FaultControl;
import com.xncoding.grpc.shipment.GetShipmentRequest;
import com.xncoding.grpc.shipment.NotifyShippedRequest;
import com.xncoding.grpc.shipment.ShipmentReply;
import com.xncoding.grpc.shipment.ShipmentServiceGrpc;
import com.xncoding.grpc.shipment.TrackEvent;
import com.xncoding.grpc.shipment.TrackShipmentRequest;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 物流运单 gRPC 服务端：两个 unary + 一个服务端流式 */
@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ShipmentGrpcService.class);

    private final Map<String, Shipment> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);
    private final FaultControl fault;

    public ShipmentGrpcService(FaultControl fault) {
        this.fault = fault;
    }

    @Override
    public void notifyShipped(NotifyShippedRequest request, StreamObserver<ShipmentReply> responseObserver) {
        if (fault.isSlow()) {
            sleep(1200);
        }
        String id = "SHP" + seq.incrementAndGet();
        store.put(id, new Shipment(id, request.getOrderNo(), request.getCarrier(), "DISPATCHED"));
        Shipment saved = store.get(id);
        responseObserver.onNext(ShipmentReply.newBuilder()
                .setShipmentId(saved.id())
                .setOrderNo(saved.orderNo())
                .setCarrier(saved.carrier())
                .setStatus(saved.status())
                .build());
        responseObserver.onCompleted();
        log.info("GRPC_SRV op=notifyShipped shipmentId={} orderNo={}", id, request.getOrderNo());
    }

    @Override
    public void getShipment(GetShipmentRequest request, StreamObserver<ShipmentReply> responseObserver) {
        Shipment saved = store.get(request.getShipmentId());
        if (saved == null) {
            throw new ShipmentNotFoundException(request.getShipmentId());
        }
        responseObserver.onNext(ShipmentReply.newBuilder()
                .setShipmentId(saved.id())
                .setOrderNo(saved.orderNo())
                .setCarrier(saved.carrier())
                .setStatus(saved.status())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void trackShipment(TrackShipmentRequest request, StreamObserver<TrackEvent> responseObserver) {
        Shipment saved = store.get(request.getShipmentId());
        if (saved == null) {
            throw new ShipmentNotFoundException(request.getShipmentId());
        }
        String[] stops = {"PICKED_UP", "IN_TRANSIT", "HUB_SORTING", "OUT_FOR_DELIVERY", "DELIVERED"};
        long base = System.currentTimeMillis();
        for (int i = 0; i < stops.length; i++) {
            if (fault.isSlow()) {
                sleep(300);
            } else {
                sleep(60);
            }
            responseObserver.onNext(TrackEvent.newBuilder()
                    .setShipmentId(saved.id())
                    .setStatus(stops[i])
                    .setLocation("NODE-" + (i + 1))
                    .setOccurredAt(base + i * 60000L)
                    .build());
        }
        responseObserver.onCompleted();
        log.info("GRPC_SRV op=trackShipment shipmentId={} events={}", saved.id(), stops.length);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Shipment(String id, String orderNo, String carrier, String status) {
    }
}
