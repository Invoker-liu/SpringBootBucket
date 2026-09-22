package com.xncoding.jackson3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：起真实 Tomcat，断言三种空值策略、自定义序列化器与反序列化容错。
 * 全部期望值来自 verify-jackson3.sh 同一轮实测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JacksonNullStrategyTest {

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
    void defaultsProbe() {
        String body = rest.get().uri("/api/jackson/defaults")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .contains("\"mapperClass\":\"tools.jackson.databind.json.JsonMapper\"")
                .contains("\"failOnUnknownProperties\":false")
                .contains("\"failOnNullForPrimitives\":true");
    }

    @Test
    void defaultOutputKeepsNullFields() {
        String body = rest.get().uri("/api/orders/2")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .contains("\"couponCode\":null")
                .contains("\"customerPhone\":null")
                .contains("\"remark\":null")
                .contains("\"discountCents\":null")
                .contains("\"amountCents\":12990");
    }

    @Test
    void classLevelNonNullOmitsAllNulls() {
        String body = rest.get().uri("/api/orders/2/non-null")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .doesNotContain(":null")
                .contains("\"customerName\":\"李四\"")
                .contains("\"amountCents\":12990");
    }

    @Test
    void fieldLevelIncludeOnlyOmitsAnnotatedField() {
        String body = rest.get().uri("/api/orders/2/field-include")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .doesNotContain("\"couponCode\"")
                .contains("\"remark\":null")
                .contains("\"discountCents\":null");
    }

    @Test
    void recordSerializationKeepsDeclarationOrderAndNulls() {
        String body = rest.get().uri("/api/orders/2/record")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .startsWith("{\"id\":2,\"orderNo\":\"SO-2026-0002\",\"customerName\"")
                .contains("\"customerPhone\":null")
                .contains("\"createdAt\":\"2026-09-20T11:05:00\"");
    }

    @Test
    void customSerializerConvertsCentsToYuan() {
        String order1 = rest.get().uri("/api/orders/1/amount")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(order1)
                .contains("\"amountYuan\":241.82")
                .contains("\"discountYuan\":20.00");

        String order2 = rest.get().uri("/api/orders/2/amount")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(order2)
                .contains("\"amountYuan\":129.90")
                .contains("\"discountYuan\":null");
    }

    @Test
    void deserializationTolerances() {
        String payload = """
                {"customerName":"王五","orderNo":"TM-2026-777","extraField":"x",
                 "couponCode":null,"quantity":1,"discountCents":null,"amount":241.82}""";
        String body = rest.post().uri("/api/orders/parse")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .contains("\"merchantOrderNo\":\"TM-2026-777\"")
                .contains("\"couponCode\":\"\"")
                .contains("\"discountCents\":0")
                .contains("\"amountCents\":24182");
    }

    @Test
    void nullIntoPrimitiveRejectedWith400() {
        String payload = """
                {"customerName":"王五","quantity":null,"amount":10.50}""";
        String body = rest.post().uri("/api/orders/parse")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("FAIL_ON_NULL_FOR_PRIMITIVES");
    }

    @Test
    void createOrderConvertsYuanToCents() {
        String payload = """
                {"customerName":"赵六","quantity":3,"amount":99.90,"couponCode":"VIP30"}""";
        String body = rest.post().uri("/api/orders")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .contains("\"amountCents\":9990")
                .contains("\"couponCode\":\"VIP30\"");
    }
}
