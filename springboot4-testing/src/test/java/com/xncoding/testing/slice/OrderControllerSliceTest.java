package com.xncoding.testing.slice;

import java.math.BigDecimal;

import com.xncoding.testing.config.GlobalExceptionHandler;
import com.xncoding.testing.order.DuplicateOrderException;
import com.xncoding.testing.order.Order;
import com.xncoding.testing.order.OrderController;
import com.xncoding.testing.order.OrderNotFoundException;
import com.xncoding.testing.order.OrderService;
import com.xncoding.testing.order.OrderStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerSliceTest {

    @Autowired
    MockMvcTester mockMvc;

    @MockitoBean
    OrderService orders;

    private static final Order SAMPLE =
            new Order(1L, "SK-T4-1", new BigDecimal("359.00"), OrderStatus.NEW,
                    java.time.Instant.parse("2026-09-20T04:00:00Z"));

    @Test
    void create_returns_201_with_location_and_body() throws Exception {
        when(orders.create(eq("SK-T4-1"), any())).thenReturn(SAMPLE);
        String body = "{\"orderNo\":\"SK-T4-1\",\"amount\":359.00}";

        assertThat(mockMvc.post().uri("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("orderNo").isEqualTo("SK-T4-1");
    }

    @Test
    void create_rejects_blank_order_no_with_400() throws Exception {
        String body = "{\"orderNo\":\"\",\"amount\":-1}";

        assertThat(mockMvc.post().uri("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(400);
    }

    @Test
    void get_missing_order_returns_404_problem_detail() {
        when(orders.getById(99L)).thenThrow(new OrderNotFoundException(99L));

        assertThat(mockMvc.get().uri("/api/orders/99"))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("type").isEqualTo("urn:problem-type:order-not-found");
    }

    @Test
    void duplicate_order_returns_409_problem_detail() {
        when(orders.create(eq("SK-DUP"), any()))
                .thenThrow(new DuplicateOrderException("SK-DUP"));

        assertThat(mockMvc.post().uri("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"SK-DUP\",\"amount\":10.00}"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("title").isEqualTo("Conflict");
    }

    @Test
    void list_returns_json_array_of_orders() {
        when(orders.list(OrderStatus.NEW)).thenReturn(java.util.List.of(SAMPLE));

        assertThat(mockMvc.get().uri("/api/orders?status=NEW"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[0].status").isEqualTo("NEW");
    }
}
