package com.xncoding.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单接口 + 超时取消任务，全连真实 MySQL。
 * 覆盖：下单 201、校验 400、查询 404、手动触发取消任务后订单被置 CANCELLED。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM po_order").update();
    }

    @Test
    void createAndFetchOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SO90001\",\"amount\":\"199.50\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_no").value("SO90001"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        mockMvc.perform(get("/api/orders/SO90001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(199.50));
    }

    @Test
    void rejectInvalidOrderNo() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"bad\",\"amount\":\"-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        mockMvc.perform(get("/api/orders/SO40440"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"));
    }

    @Test
    void manualTriggerCancelsBackdatedOrders() throws Exception {
        // 直接造一条 60 秒前的未支付订单，绕过 created_at 默认值
        jdbc.sql("INSERT INTO po_order (order_no, amount, created_at) VALUES (?, ?, ?)")
                .param("SO90002").param("88.00").param(LocalDateTime.now().minusSeconds(60))
                .update();

        mockMvc.perform(post("/api/scheduler/trigger/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/orders/SO90002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancel_time").isNotEmpty());
    }
}
