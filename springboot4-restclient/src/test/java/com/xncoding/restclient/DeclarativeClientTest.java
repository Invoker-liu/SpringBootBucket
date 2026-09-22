package com.xncoding.restclient;

import com.xncoding.restclient.api.LogisticsApi;
import com.xncoding.restclient.api.RiskApi;
import com.xncoding.restclient.model.RiskRequest;
import com.xncoding.restclient.model.RiskResult;
import com.xncoding.restclient.model.Waybill;
import com.xncoding.restclient.model.WaybillRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @RestClientTest 切片：注入的 RestClient.Builder 已挂 MockRestServiceServer。
 * 生产配置里 requestFactory 会被 JDK 工厂覆盖、绕过 mock，所以这里用
 * 测试专用配置从 Builder 直接生成代理，只验证接口映射与错误路径。
 */
@RestClientTest
@Import(DeclarativeClientTest.TestClients.class)
class DeclarativeClientTest {

    @Autowired
    LogisticsApi logisticsApi;

    @Autowired
    RiskApi riskApi;

    @Autowired
    MockRestServiceServer server;

    @TestConfiguration
    static class TestClients {

        /** 只 build 一次 RestClient：定制器绑定的 mock 服务端只允许绑定一个实例 */
        @Bean
        RestClient upstreamRestClient(RestClient.Builder builder) {
            return builder.clone()
                    .baseUrl("http://localhost:18141")
                    .defaultHeader("X-Source", "order-service")
                    .build();
        }

        @Bean
        LogisticsApi logisticsApi(RestClient upstreamRestClient) {
            return proxyOf(upstreamRestClient, LogisticsApi.class);
        }

        @Bean
        RiskApi riskApi(RestClient upstreamRestClient) {
            return proxyOf(upstreamRestClient, RiskApi.class);
        }

        private static <T> T proxyOf(RestClient restClient, Class<T> apiType) {
            return HttpServiceProxyFactory.builder()
                    .exchangeAdapter(RestClientAdapter.create(restClient))
                    .build()
                    .createClient(apiType);
        }
    }

    @Test
    void waybillPost_carriesBodyAndHeader() {
        server.expect(requestTo("http://localhost:18141/logistics/waybills"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source", "order-service"))
                .andExpect(jsonPath("$.orderNo").value("SK-2001"))
                .andExpect(jsonPath("$.itemCount").value(3))
                .andRespond(withSuccess(
                        """
                        {"waybillNo":"WB90001","carrier":"STO","status":"CREATED"}
                        """, MediaType.APPLICATION_JSON));

        Waybill waybill = logisticsApi.createWaybill(new WaybillRequest("SK-2001", 3));

        assertThat(waybill.waybillNo()).isEqualTo("WB90001");
        assertThat(waybill.carrier()).isEqualTo("STO");
        server.verify();
    }

    @Test
    void waybillGet_mapsPathVariable() {
        server.expect(requestTo("http://localhost:18141/logistics/waybills/WB90001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"waybillNo":"WB90001","carrier":"STO","status":"IN_TRANSIT"}
                        """, MediaType.APPLICATION_JSON));

        Waybill waybill = logisticsApi.getWaybill("WB90001");

        assertThat(waybill.status()).isEqualTo("IN_TRANSIT");
        server.verify();
    }

    @Test
    void riskCheck_sendsAmountAndReadsResult() {
        server.expect(requestTo("http://localhost:18141/risk/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source", "order-service"))
                .andExpect(jsonPath("$.orderNo").value("SK-2002"))
                .andExpect(jsonPath("$.amount").value(299.0))
                .andRespond(withSuccess(
                        """
                        {"pass":true,"score":12,"reason":"ok"}
                        """, MediaType.APPLICATION_JSON));

        RiskResult result = riskApi.check(new RiskRequest("SK-2002", new BigDecimal("299.00")));

        assertThat(result.pass()).isTrue();
        assertThat(result.score()).isEqualTo(12);
        server.verify();
    }

    @Test
    void riskCheck_non2xx_throwsRestClientResponseException() {
        server.expect(requestTo("http://localhost:18141/risk/check"))
                .andRespond(withServerError()
                        .body("{\"error\":\"internal\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> riskApi.check(new RiskRequest("SK-2003", new BigDecimal("10.00"))))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(e -> assertThat(((RestClientResponseException) e).getStatusCode())
                        .isEqualTo(HttpStatusCode.valueOf(500)));
        server.verify();
    }
}
