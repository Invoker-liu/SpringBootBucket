package com.xncoding.grpc;

import com.xncoding.grpc.shipment.GetShipmentRequest;
import com.xncoding.grpc.shipment.NotifyShippedRequest;
import com.xncoding.grpc.shipment.ShipmentReply;
import com.xncoding.grpc.shipment.ShipmentServiceGrpc;
import com.xncoding.grpc.shipment.TrackEvent;
import com.xncoding.grpc.shipment.TrackShipmentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** gRPC 面切片测试：@AutoConfigureTestGrpcTransport 把服务端与通道全换成进程内传输 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
class ShipmentGrpcInProcessTest {

    @Autowired
    ShipmentServiceGrpc.ShipmentServiceBlockingStub stub;

    @Test
    void notifyThenGet_roundTrip() {
        ShipmentReply created = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .notifyShipped(NotifyShippedRequest.newBuilder()
                        .setOrderNo("SK-T001").setCarrier("STO").setItemCount(2).build());
        assertThat(created.getShipmentId()).startsWith("SHP");
        assertThat(created.getStatus()).isEqualTo("DISPATCHED");

        ShipmentReply fetched = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getShipment(GetShipmentRequest.newBuilder()
                        .setShipmentId(created.getShipmentId()).build());
        assertThat(fetched.getOrderNo()).isEqualTo("SK-T001");
        assertThat(fetched.getCarrier()).isEqualTo("STO");
    }

    @Test
    void trackShipment_streamsFiveEvents() {
        ShipmentReply created = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .notifyShipped(NotifyShippedRequest.newBuilder()
                        .setOrderNo("SK-T002").setCarrier("YTO").setItemCount(1).build());
        Iterator<TrackEvent> events = stub.withDeadlineAfter(2000, TimeUnit.MILLISECONDS)
                .trackShipment(TrackShipmentRequest.newBuilder()
                        .setShipmentId(created.getShipmentId()).build());
        int count = 0;
        String last = "";
        while (events.hasNext()) {
            last = events.next().getStatus();
            count++;
        }
        assertThat(count).isEqualTo(5);
        assertThat(last).isEqualTo("DELIVERED");
    }

    @Test
    void unknownShipment_adviceMapsToNotFound() {
        assertThatThrownBy(() -> stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getShipment(GetShipmentRequest.newBuilder().setShipmentId("SHP-NOPE").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e ->
                        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }
}
