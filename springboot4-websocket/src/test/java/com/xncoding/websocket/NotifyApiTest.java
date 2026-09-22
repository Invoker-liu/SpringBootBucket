package com.xncoding.websocket;

import com.xncoding.websocket.ws.SessionRegistry;
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
 * HTTP 侧接口：无 WS 客户端时广播送达数为 0，统计结构与状态码固定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotifyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionRegistry registry;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM po_order").update();
    }

    @Test
    void broadcastEndpointReportsDeliveredCount() throws Exception {
        mockMvc.perform(post("/api/notify/broadcast")
                        .param("orderNo", "WS90001").param("amount", "66.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("order"))
                .andExpect(jsonPath("$.orderNo").value("WS90001"))
                .andExpect(jsonPath("$.delivered").value(0));
    }

    @Test
    void createOrderFallsToDatabaseAndBroadcasts() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"WS90002\",\"amount\":\"199.50\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("WS90002"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.broadcast").value(0));
    }

    @Test
    void statsExposeCounters() throws Exception {
        mockMvc.perform(get("/api/notify/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(0))
                .andExpect(jsonPath("$.connected").isNumber())
                .andExpect(jsonPath("$.closed").isNumber())
                .andExpect(jsonPath("$.pingReceived").isNumber())
                .andExpect(jsonPath("$.pongSent").isNumber());
    }
}
