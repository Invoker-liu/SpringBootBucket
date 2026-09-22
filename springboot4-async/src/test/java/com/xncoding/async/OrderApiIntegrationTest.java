package com.xncoding.async;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单接口的异步与同步两条路径：响应时间差就是这篇教程要解决的问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM po_order").update();
    }

    @Test
    void asyncPlaceOrderReturnsQuickly() throws Exception {
        // 三件慢事并行投出去就返回，毫秒级（同步版要 2.1 秒）
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SO90001\",\"amount\":\"199.50\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("SO90001"))
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.mode").value("async"))
                .andExpect(jsonPath("$.elapsedMillis").value(org.hamcrest.Matchers.lessThan(1500)));
    }

    @Test
    void syncPlaceOrderTakesOverTwoSeconds() throws Exception {
        // 同步基线：600 + 700 + 800 毫秒串行做完才返回
        mockMvc.perform(post("/api/orders/sync")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SO90002\",\"amount\":\"88.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("sync"))
                .andExpect(jsonPath("$.elapsedMillis").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2000)));
    }

    @Test
    void invalidOrderNoReturns400() throws Exception {
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
}
