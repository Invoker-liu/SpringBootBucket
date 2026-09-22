package com.xncoding.async;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步主流程的运行期行为：并行线程、任务台账、自调用失效、ThreadLocal 断层。
 * 异步断言全部用 Awaitility 轮询台账，不打时间的主意。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM po_order").update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskBody(String taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private String createAsyncOrder(String orderNo, String amount) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"" + orderNo + "\",\"amount\":\"" + amount + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = mapper.readTree(created.getResponse().getContentAsString());
        return node.get("taskId").asText();
    }

    @Test
    void threeSlowJobsRunInParallelBizThreads() throws Exception {
        String taskId = createAsyncOrder("SO91001", "299.00");

        // 三个任务全部 DONE 才继续，最长的 800ms，留足余量
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> body = taskBody(taskId);
            assertThat(body.get("status")).isEqualTo("DONE");
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
            assertThat(entries).hasSize(3);
        });

        Map<String, Object> body = taskBody(taskId);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        CopyOnWriteArrayList<String> threads = new CopyOnWriteArrayList<>();
        for (Map<String, Object> entry : entries) {
            assertThat(entry.get("status")).isEqualTo("DONE");
            String thread = String.valueOf(entry.get("thread"));
            assertThat(thread).startsWith("biz-");
            threads.add(thread);
        }
        // 核心池 4 根线程，三个任务同时投递 → 至少两条不同线程并行
        assertThat(threads.stream().distinct().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void taskStatusEndpointAggregatesAndServesPoints() throws Exception {
        String taskId = createAsyncOrder("SO91002", "150.00");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> body = taskBody(taskId);
            assertThat(body.get("status")).isEqualTo("DONE");
            assertThat(body.get("points")).isEqualTo(10);
        });
    }

    @Test
    void selfInvocationDoesNotGoAsync() throws Exception {
        // this 调用走不到代理：selfCallThread 与 httpThread 相同（同步），
        // 代理调用的 proxiedCallThread 是 biz- 线程（真异步）
        MvcResult result = mockMvc.perform(post("/api/probe/self-invocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfCallAsync").value("false"))
                .andExpect(jsonPath("$.proxiedCallThread").value(
                        org.hamcrest.Matchers.startsWith("biz-")))
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("selfCallThread")).isEqualTo(body.get("httpThread"));
    }

    @Test
    void threadLocalDoesNotCrossThreads() throws Exception {
        // HTTP 线程写入的 traceId 在异步线程读不到
        mockMvc.perform(post("/api/probe/thread-local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpValue").isNotEmpty())
                .andExpect(jsonPath("$.asyncValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.asyncThread").value(org.hamcrest.Matchers.startsWith("biz-")));
    }
}
