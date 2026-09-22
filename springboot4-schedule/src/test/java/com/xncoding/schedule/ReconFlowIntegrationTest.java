package com.xncoding.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对账文件登记 → cron 扫描任务处理，全连真实 MySQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM recon_file").update();
    }

    @Test
    void registerDuplicateFileRejected() throws Exception {
        mockMvc.perform(post("/api/recon/files").param("fileName", "recon-mon.csv"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/recon/files").param("fileName", "recon-mon.csv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:business-rule-violation"));
    }

    @Test
    void manualTriggerProcessesPendingFiles() throws Exception {
        mockMvc.perform(post("/api/recon/files").param("fileName", "recon-tue.csv"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/scheduler/trigger/recon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/recon/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DONE"))
                .andExpect(jsonPath("$[0].processed_at").isNotEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicCronReflectsImmediately() throws Exception {
        mockMvc.perform(get("/api/scheduler/cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0/5 * * * * *"));

        mockMvc.perform(post("/api/scheduler/cron")
                        .contentType("application/json")
                        .content("{\"cron\":\"0/2 * * * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0/2 * * * * *"));

        mockMvc.perform(get("/api/scheduler/cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("0/2 * * * * *"));

        // 改回默认，别把节奏留给后面的用例
        mockMvc.perform(post("/api/scheduler/cron")
                        .contentType("application/json")
                        .content("{\"cron\":\"0/5 * * * * *\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void illegalCronRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/scheduler/cron")
                        .contentType("application/json")
                        .content("{\"cron\":\"not-a-cron\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void journalRecordsManualTrigger() throws Exception {
        mockMvc.perform(post("/api/scheduler/trigger/recon"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/scheduler/journal?job=recon&limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].job").value("recon"))
                .andExpect(jsonPath("$[0].trigger").value("manual"))
                .andExpect(jsonPath("$[0].status").value("ok"));
    }
}
