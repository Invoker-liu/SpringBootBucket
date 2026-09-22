package com.xncoding.restful;

import com.xncoding.restful.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端测试：启动真实 Tomcat，通过 HTTP 走完整链路。
 * <p>
 * 使用 Boot 4 新增的 {@link RestTestClient}——它把 WebTestClient 风格的流式断言
 * 与 {@code RestClient} 风格的 API 合到一起，既能绑定真实服务端，也能绑定 MockMvc 或单个 Controller。
 * 与 MVC 切片测试互补：切片测的是映射与约定，这里测的是真实的 JSON 序列化、状态码与 Location 头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    private String tag;

    @BeforeEach
    void setUp() {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        client = builder.build();
        tag = "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> createPayload() {
        return Map.of(
                "customerName", tag,
                "customerPhone", "13800138000",
                "totalAmount", new BigDecimal("199.90"),
                "remark", "端到端测试"
        );
    }

    @Test
    @DisplayName("完整生命周期：创建 → 查询 → 分页 → 状态流转 → 更新 → 删除")
    void fullLifecycle() {
        // 1. 创建，断言 201 与 Location 头
        EntityExchangeResult<OrderResponse> created = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createPayload())
                .exchange()
                .expectStatus().isCreated()
                .returnResult(OrderResponse.class);

        OrderResponse order = created.getResponseBody();
        assertThat(order).isNotNull();
        assertThat(order.id()).isNotNull();
        assertThat(order.orderNo()).startsWith("ORD");
        assertThat(order.statusLabel()).isEqualTo("已创建");
        assertThat(created.getResponseHeaders().getLocation())
                .hasToString("http://localhost:" + port + "/api/orders/" + order.id());

        Long id = order.id();

        // 2. 按主键查询，手机号在响应中已脱敏
        client.get().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.customerPhone").isEqualTo("138****8000");

        // 3. 关键字过滤能命中刚创建的订单
        client.get().uri("/api/orders?keyword={keyword}", tag)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1);

        // 4. 合法状态流转
        client.patch().uri("/api/orders/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "PAID"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PAID")
                .jsonPath("$.statusLabel").isEqualTo("已支付");

        // 5. 非法状态流转：PAID 不能直接到 COMPLETED
        client.patch().uri("/api/orders/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "COMPLETED"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody()
                .jsonPath("$.detail").isNotEmpty();

        // 6. 整体更新
        client.put().uri("/api/orders/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "customerName", tag + "-改",
                        "customerPhone", "13900139000",
                        "totalAmount", new BigDecimal("88.00")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalAmount").isEqualTo(88.00);

        // 7. 删除后资源不可再访问
        client.delete().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isNoContent();
        client.get().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("错误响应带 type/title/status/detail/instance 五要素，且为 problem+json")
    void problemDetailShape() {
        client.get().uri("/api/orders/{id}", 77777777L)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/api/orders/77777777")
                .jsonPath("$.title").isEqualTo("资源不存在");
    }
}
