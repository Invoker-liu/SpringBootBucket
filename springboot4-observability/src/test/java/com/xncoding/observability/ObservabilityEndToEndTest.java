package com.xncoding.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测性端到端测试：真实启动 Tomcat（随机端口），验证自定义指标、
 * http.server.requests 失败计数、prometheus 文本端点与 health 明细。
 * 测试期间关闭三个 OTLP 导出器，避免无收集器时的告警噪音。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.otlp.enabled=false"
})
class ObservabilityEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private long metricCount(String metricsJson) {
        // /actuator/metrics/{name} 的格式：measurements[{"statistic":"COUNT","value":2.0}]
        Matcher m = Pattern.compile("\"statistic\":\"COUNT\",\"value\":\\s*([0-9.E-]+)").matcher(metricsJson);
        return m.find() ? (long) Double.parseDouble(m.group(1)) : -1;
    }

    private String postOrder() {
        return client.post().uri("/api/orders")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"customer\":\"张三\",\"amount\":199.50}")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatusCode.valueOf(200))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    @DisplayName("health 端点默认可访问且 show-details 生效")
    void healthShowsDetails() {
        String body = client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("\"status\":\"UP\"").contains("diskSpace");
    }

    @Test
    @DisplayName("创建订单后 orders.placed 计数器递增")
    void createdCounterIncrements() {
        postOrder();
        postOrder();
        String body = client.get().uri("/actuator/metrics/orders.placed")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("\"name\":\"orders.placed\"");
        assertThat(metricCount(body)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("process 接口计时后 orders.processing Timer 记录样本")
    void processingTimerRecords() {
        String created = postOrder();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        client.get().uri("/api/orders/" + id + "/process")
                .exchange()
                .expectStatus().isOk();
        String body = client.get().uri("/actuator/metrics/orders.processing")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("\"name\":\"orders.processing\"");
        assertThat(metricCount(body)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("fail 接口返回 500 且 http.server.requests 记录 SERVER_ERROR")
    void failEndpointCountsServerError() {
        String created = postOrder();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        client.get().uri("/api/orders/" + id + "/fail")
                .exchange()
                .expectStatus().isEqualTo(HttpStatusCode.valueOf(500));
        // http.server.requests 的 uri 标签是路由模板（带 {id}），metrics 查询参数里传大括号
        // 会被 RestTestClient 当 URI 变量展开或二次编码，改从 prometheus 文本断言最稳
        String body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("outcome=\"SERVER_ERROR\"").contains("uri=\"/api/orders/{id}/fail\"");
    }

    @Test
    @DisplayName("prometheus 端点输出文本格式，自定义计数器带 _total 后缀")
    void prometheusScrapeExposesCustomMetrics() {
        postOrder();
        String body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("# TYPE orders_placed_total counter").contains("orders_placed_total");
        // http_server_requests 默认无直方图配置，导出为 _count/_sum/_max，不出现 histogram TYPE 行
        assertThat(body).contains("http_server_requests_seconds_count");
    }

    @Test
    @DisplayName("不存在的订单返回 404")
    void missingOrderIsNotFound() {
        client.get().uri("/api/orders/999")
                .exchange()
                .expectStatus().isEqualTo(HttpStatusCode.valueOf(404));
    }
}
