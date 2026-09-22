package com.xncoding.async;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Async 方法异常的去处分流：void 进 AsyncUncaughtExceptionHandler（计数 + 台账），
 * CompletableFuture 待在 future 里、get() 才浮出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AsyncConfigurer asyncConfigurer;

    @Autowired
    private com.xncoding.async.stats.UncaughtCounter uncaughtCounter;

    @Test
    void voidExceptionHitsUncaughtHandlerAndMarksLedgerFailed() throws Exception {
        long before = uncaughtCounter.count();

        String body = mockMvc.perform(post("/api/probe/void-exception"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String taskId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("taskId").asText();

        // 处理器计数恰好 +1，台账标 FAILED，异常类型留在 error 字段
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(uncaughtCounter.count()).isEqualTo(before + 1);
            String response = mockMvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).contains("\"status\":\"FAILED\"");
            assertThat(response).contains("java.lang.IllegalStateException");
            assertThat(response).contains("短信通道 503");
        });
    }

    @Test
    void futureExceptionStaysInFutureUntilGet() throws Exception {
        String body = mockMvc.perform(post("/api/probe/future-exception"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String taskId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("taskId").asText();

        // future 完成（异常态）之后，查询接口 get() 捞到 ExecutionException
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String response = mockMvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).contains("java.util.concurrent.ExecutionException");
            assertThat(response).contains("积分服务超时");
        });
    }

    @Test
    void uncaughtHandlerContractDoesNotThrow() throws Exception {
        // 处理器契约：吞掉异常不向外抛，处理完即返回
        long before = uncaughtCounter.count();
        AsyncUncaughtExceptionHandler handler = asyncConfigurer.getAsyncUncaughtExceptionHandler();
        Method anyMethod = Object.class.getMethods()[0];
        handler.handleUncaughtException(new RuntimeException("契约测试"), anyMethod, "T-contract");
        assertThat(uncaughtCounter.count()).isEqualTo(before + 1);
    }
}
