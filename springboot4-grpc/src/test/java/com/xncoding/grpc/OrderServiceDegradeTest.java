package com.xncoding.grpc;

import com.xncoding.grpc.client.LogisticsClient;
import com.xncoding.grpc.client.LogisticsClient.ShipmentView;
import com.xncoding.grpc.service.OrderService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 订单降级编排：物流 gRPC 失败返回空，下单链路照常走完并打降级标记 */
class OrderServiceDegradeTest {

    @Test
    void notifyFails_orderStillPlacedWithDegradeMarker() {
        LogisticsClient logistics = mock(LogisticsClient.class);
        when(logistics.notifyShipped(anyString(), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        OrderService service = new OrderService(logistics, new com.xncoding.grpc.metrics.CallMetrics());

        OrderService.Order order = service.placeOrder("SK-T003", new BigDecimal("42.00"), 1);

        assertThat(order.status()).isEqualTo("DEGRADE_DEADLINE");
        assertThat(order.shipment()).isNull();
    }

    @Test
    void notifyOk_carriesShipmentFields() {
        LogisticsClient logistics = mock(LogisticsClient.class);
        when(logistics.notifyShipped(anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(new ShipmentView("SHP0077", "SK-T004", "STO", "DISPATCHED")));
        OrderService service = new OrderService(logistics, new com.xncoding.grpc.metrics.CallMetrics());

        OrderService.Order order = service.placeOrder("SK-T004", new BigDecimal("99.00"), 3);

        assertThat(order.status()).isEqualTo("OK");
        assertThat(order.shipment().shipmentId()).isEqualTo("SHP0077");
        assertThat(order.shipment().carrier()).isEqualTo("STO");
    }
}
