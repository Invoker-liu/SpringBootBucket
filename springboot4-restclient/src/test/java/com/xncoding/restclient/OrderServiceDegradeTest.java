package com.xncoding.restclient;

import com.xncoding.restclient.api.LogisticsApi;
import com.xncoding.restclient.api.RiskApi;
import com.xncoding.restclient.model.RiskRequest;
import com.xncoding.restclient.model.RiskResult;
import com.xncoding.restclient.model.Waybill;
import com.xncoding.restclient.model.WaybillRequest;
import com.xncoding.restclient.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 降级路径单测：风控超时/非 2xx 都不让下单链路断掉 */
class OrderServiceDegradeTest {

    private final LogisticsApi logisticsApi = mock(LogisticsApi.class);
    private final RiskApi riskApi = mock(RiskApi.class);

    private OrderService service() {
        return new OrderService(riskApi, logisticsApi, new com.xncoding.restclient.metrics.CallMetrics());
    }

    @Test
    void riskTimeout_degradesAndStillCreatesWaybill() {
        when(riskApi.check(any()))
                .thenThrow(new ResourceAccessException("Read timed out",
                        new HttpTimeoutException("no response within 1500 ms")));
        when(logisticsApi.createWaybill(any()))
                .thenReturn(new Waybill("WB90010", "STO", "CREATED"));

        OrderService.PlacedOrder order = service().placeOrder("SK-3001", new BigDecimal("88.00"), 2);

        assertThat(order.risk().decision()).isEqualTo("DEGRADE_TIMEOUT");
        assertThat(order.risk().riskLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(order.waybill()).isNotNull();
        assertThat(order.waybill().waybillNo()).isEqualTo("WB90010");
    }

    @Test
    void riskServerError_degradesWithStatus() {
        when(riskApi.check(any()))
                .thenThrow(new RestClientResponseException("500", 500, "Internal Server Error", null, null, null));
        when(logisticsApi.createWaybill(any()))
                .thenReturn(new Waybill("WB90011", "STO", "CREATED"));

        OrderService.PlacedOrder order = service().placeOrder("SK-3002", new BigDecimal("100.00"), 1);

        assertThat(order.risk().decision()).isEqualTo("DEGRADE_ERROR");
        assertThat(order.risk().reason()).contains("500");
        assertThat(order.waybill().waybillNo()).isEqualTo("WB90011");
    }

    @Test
    void waybillFailure_doesNotBreakOrder() {
        when(riskApi.check(any())).thenReturn(new RiskResult(true, 5, "ok"));
        when(logisticsApi.createWaybill(any()))
                .thenThrow(new RestClientResponseException("503", 503, "Service Unavailable", null, null, null));

        OrderService service = service();
        OrderService.PlacedOrder order = service.placeOrder("SK-3003", new BigDecimal("20.00"), 1);

        assertThat(order.risk().decision()).isEqualTo("PASS");
        assertThat(order.waybill()).isNull();
        assertThat(service.get("SK-3003")).isNotNull();
    }
}
