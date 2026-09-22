package com.xncoding.grpc.client;

import com.xncoding.grpc.metrics.CallMetrics;
import com.xncoding.grpc.shipment.GetShipmentRequest;
import com.xncoding.grpc.shipment.NotifyShippedRequest;
import com.xncoding.grpc.shipment.ShipmentReply;
import com.xncoding.grpc.shipment.ShipmentServiceGrpc;
import com.xncoding.grpc.shipment.TrackEvent;
import com.xncoding.grpc.shipment.TrackShipmentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 物流服务 gRPC 客户端：deadline 实配、Status 分流、计数与耗时记录 */
@Component
public class LogisticsClient {

    private static final Logger log = LoggerFactory.getLogger(LogisticsClient.class);

    /** unary 调用 deadline：超过即降级，订单链路不能被物流拖死 */
    private static final long NOTIFY_DEADLINE_MS = 500;
    private static final long GET_DEADLINE_MS = 500;
    /** 流式调用留宽一点：5 个事件每个最多 300ms 也要能走完 */
    private static final long TRACK_DEADLINE_MS = 2000;

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub stub;
    private final CallMetrics metrics;

    public LogisticsClient(ShipmentServiceGrpc.ShipmentServiceBlockingStub stub, CallMetrics metrics) {
        this.stub = stub;
        this.metrics = metrics;
    }

    public Optional<ShipmentView> notifyShipped(String orderNo, String carrier, int itemCount) {
        metrics.incNotifyCalls();
        long t0 = System.nanoTime();
        try {
            ShipmentReply reply = stub.withDeadlineAfter(NOTIFY_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .notifyShipped(NotifyShippedRequest.newBuilder()
                            .setOrderNo(orderNo)
                            .setCarrier(carrier)
                            .setItemCount(itemCount)
                            .build());
            long ms = elapsed(t0);
            metrics.recordNotifyLatency(ms);
            metrics.incNotifyOk();
            log.info("GRPC_CLI op=notifyShipped shipmentId={} elapsedMs={}", reply.getShipmentId(), ms);
            return Optional.of(ShipmentView.of(reply));
        } catch (StatusRuntimeException e) {
            long ms = elapsed(t0);
            metrics.recordNotifyLatency(ms);
            metrics.incNotifyDegrades();
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                metrics.incDeadlineExceeded();
            }
            log.warn("GRPC_DEGRADE op=notifyShipped code={} elapsedMs={}", e.getStatus().getCode(), ms);
            return Optional.empty();
        }
    }

    public Optional<ShipmentView> getShipment(String shipmentId) {
        metrics.incGetCalls();
        try {
            ShipmentReply reply = stub.withDeadlineAfter(GET_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getShipment(GetShipmentRequest.newBuilder().setShipmentId(shipmentId).build());
            return Optional.of(ShipmentView.of(reply));
        } catch (StatusRuntimeException e) {
            // NOT_FOUND 是业务结论不是故障：映射成空值交给 HTTP 层回 404
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                metrics.incNotFoundMapped();
                return Optional.empty();
            }
            throw e;
        }
    }

    public List<TrackEventView> trackShipment(String shipmentId) {
        metrics.incTrackCalls();
        Iterator<TrackEvent> events = stub.withDeadlineAfter(TRACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .trackShipment(TrackShipmentRequest.newBuilder().setShipmentId(shipmentId).build());
        List<TrackEventView> list = new ArrayList<>();
        events.forEachRemaining(e -> list.add(new TrackEventView(
                e.getStatus(), e.getLocation(), e.getOccurredAt())));
        metrics.addStreamEvents(list.size());
        return list;
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public record ShipmentView(String shipmentId, String orderNo, String carrier, String status) {

        static ShipmentView of(ShipmentReply reply) {
            return new ShipmentView(reply.getShipmentId(), reply.getOrderNo(),
                    reply.getCarrier(), reply.getStatus());
        }
    }

    public record TrackEventView(String status, String location, long occurredAt) {
    }
}
