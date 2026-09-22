package com.xncoding.testing.e2e;

import java.math.BigDecimal;
import java.time.Instant;

import com.xncoding.testing.order.Order;
import com.xncoding.testing.order.OrderRepository;
import com.xncoding.testing.order.OrderStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiE2eTest {

    @LocalServerPort
    int port;

    @Autowired
    OrderRepository orders;

    RestTestClient rest;

    OrderApiE2eTest() {
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        if (rest == null) {
            rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }
    }

    @Test
    void full_lifecycle_create_pay_list_delete() {
        String orderNo = "SK-E2E-" + System.nanoTime();

        rest.post().uri("/api/orders")
                .body(new java.util.HashMap<>(java.util.Map.of(
                        "orderNo", orderNo, "amount", new BigDecimal("129.90"))))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNumber()
                .jsonPath("$.status").isEqualTo("NEW")
                .jsonPath("$.amount").isEqualTo(129.90);

        long id = orders.findAll(OrderStatus.NEW).stream()
                .filter(o -> o.orderNo().equals(orderNo))
                .findFirst().orElseThrow().id();

        rest.post().uri("/api/orders/{id}/pay", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PAID");

        rest.get().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderNo").isEqualTo(orderNo);

        rest.delete().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void duplicate_order_no_surfaces_as_409_problem_json() {
        String orderNo = "SK-E2E-DUP-" + System.nanoTime();
        orders.insert(new Order(null, orderNo, BigDecimal.TEN, OrderStatus.NEW, Instant.now()));

        rest.post().uri("/api/orders")
                .body(new java.util.HashMap<>(java.util.Map.of(
                        "orderNo", orderNo, "amount", new BigDecimal("10.00"))))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:problem-type:duplicate-order");
    }

    @Test
    void validation_failure_returns_400_with_error_shape() {
        rest.post().uri("/api/orders")
                .body(new java.util.HashMap<>(java.util.Map.of(
                        "orderNo", "", "amount", new BigDecimal("-5"))))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void filter_by_status_keeps_contract() {
        String orderNo = "SK-E2E-F-" + System.nanoTime();
        orders.insert(new Order(null, orderNo, BigDecimal.ONE, OrderStatus.NEW, Instant.now()));

        rest.get().uri("/api/orders?status=NEW")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].orderNo").value(v -> assertThat(v.toString()).contains(orderNo));
    }
}
