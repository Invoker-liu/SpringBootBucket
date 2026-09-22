package com.xncoding.jackson3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局策略轮：default-property-inclusion=non_null 经测试属性开启，
 * 验证普通 DTO 与 record 同时受控。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.default-property-inclusion=non_null")
class GlobalInclusionPropertyTest {

    @LocalServerPort
    private int port;

    private RestTestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void globalNonNullOmitsNullsForPlainDto() {
        String body = rest.get().uri("/api/orders/2")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .doesNotContain(":null")
                .contains("\"customerName\":\"李四\"");
    }

    @Test
    void globalNonNullAlsoAppliesToRecord() {
        String body = rest.get().uri("/api/orders/2/record")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .doesNotContain(":null")
                .contains("\"createdAt\":\"2026-09-20T11:05:00\"");
    }
}
