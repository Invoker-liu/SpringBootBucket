package com.xncoding.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报表任务与慢调用任务：手动触发的完整执行路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportAndBlacklistIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM po_order").update();
        jdbc.sql("DELETE FROM daily_report").update();
    }

    @Test
    void reportTriggerAggregatesOrders() throws Exception {
        jdbc.sql("INSERT INTO po_order (order_no, amount) VALUES ('SO70001', '12.34'), ('SO70002', '67.66')")
                .update();

        mockMvc.perform(post("/api/scheduler/trigger/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/reports/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_count").value(2))
                .andExpect(jsonPath("$.total_amount").value(80.00));
    }

    @Test
    void reportBeforeFirstRunReturns404() throws Exception {
        mockMvc.perform(get("/api/reports/today"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blacklistSlowCallCostsConfiguredMillis() throws Exception {
        try {
            mockMvc.perform(post("/api/scheduler/blacklist/slow")
                            .contentType("application/json")
                            .content("{\"on\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slow").value(true));

            // 慢调用把 HTTP 线程睡了 8 秒，总耗时必然 ≥ 8 秒
            mockMvc.perform(post("/api/scheduler/trigger/blacklist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.costMillis", greaterThanOrEqualTo(8000)));
        } finally {
            mockMvc.perform(post("/api/scheduler/blacklist/slow")
                            .contentType("application/json")
                            .content("{\"on\":false}"))
                    .andExpect(status().isOk());
        }
    }
}
