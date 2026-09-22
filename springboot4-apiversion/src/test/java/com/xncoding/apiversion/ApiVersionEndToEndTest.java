package com.xncoding.apiversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 版本管理端到端测试：真实启动 Tomcat（随机端口），逐条验证取值单中的行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiVersionEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private String get(String url, String apiVersion) {
        var spec = client.get().uri(url);
        if (apiVersion != null) {
            spec = spec.header("X-Api-Version", apiVersion);
        }
        return spec.exchange()
                .expectStatus()
                .isEqualTo(HttpStatusCode.valueOf(200))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private HttpStatusCode status(String url, String apiVersion) {
        var spec = client.get().uri(url);
        if (apiVersion != null) {
            spec = spec.header("X-Api-Version", apiVersion);
        }
        return spec.exchange()
                .expectBody(String.class)
                .returnResult()
                .getStatus();
    }

    @Test
    @DisplayName("不带版本：default=1 生效，落 v1 快照结构")
    void noVersionFallsBackToDefault() {
        assertThat(status("/api/orders/1", null)).isEqualTo(HttpStatusCode.valueOf(200));
        String body = get("/api/orders/1", null);
        assertThat(body).contains("unitPrice").contains("customerName");
        assertThat(body).doesNotContain("\"amount\"");
    }

    @Test
    @DisplayName("header 路线：X-Api-Version: 1 落 v1，: 2 落 v2")
    void headerRouteSelectsVersion() {
        String v1 = get("/api/orders/1", "1");
        assertThat(v1).contains("customerPhone").contains("unitPrice");

        String v2 = get("/api/orders/1", "2");
        assertThat(v2).contains("\"customer\":{\"name\"").contains("\"items\"");
    }

    @Test
    @DisplayName("query 路线：?api-version=2 与 header 等价")
    void queryRouteWorks() {
        String body = get("/api/orders/1?api-version=2", null);
        assertThat(body).contains("\"amount\"");
    }

    @Test
    @DisplayName("非法版本 abc：400 InvalidApiVersionException")
    void invalidVersionRejected() {
        String body = client.get().uri("/api/orders/1")
                .header("X-Api-Version", "abc")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatusCode.valueOf(400))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("Invalid API version");
    }

    @Test
    @DisplayName("白名单外的版本 9：400")
    void versionOutsideWhitelistRejected() {
        assertThat(status("/api/orders/1", "9")).isEqualTo(HttpStatusCode.valueOf(400));
    }

    @Test
    @DisplayName("订单接口收到白名单内但无映射的版本 3：400")
    void unmappedButWhitelistedVersionOnOrders() {
        assertThat(status("/api/orders/1", "3")).isEqualTo(HttpStatusCode.valueOf(400));
    }

    @Test
    @DisplayName("版本范围 2+：接受 2 和 3，拒绝白名单外的 2.5 与不满足范围的 1")
    void baselineRangeSemantics() {
        assertThat(status("/api/version/echo", "3")).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(status("/api/version/echo", "2")).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(status("/api/version/echo", "2.5")).isEqualTo(HttpStatusCode.valueOf(400));
        assertThat(status("/api/version/echo", "1")).isEqualTo(HttpStatusCode.valueOf(404));
    }

    @Test
    @DisplayName("两版本列表同路径并存，结构互斥")
    void bothVersionsCoexistOnSamePath() {
        String v1 = get("/api/orders", "1");
        String v2 = get("/api/orders", "2");
        assertThat(v1).contains("customerName").doesNotContain("\"items\"");
        assertThat(v2).contains("\"items\"").doesNotContain("customerName");
    }
}
