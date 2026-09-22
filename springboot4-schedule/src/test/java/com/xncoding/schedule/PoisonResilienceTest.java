package com.xncoding.schedule;

import com.xncoding.schedule.job.ChaosJobs;
import com.xncoding.schedule.scheduling.SchedulerErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常处理语义实证：poison 开着时任务抛异常，
 * 手动触发不 500、调度不重试、ErrorHandler 接住后下一次照常。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PoisonResilienceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChaosJobs chaosJobs;

    @Test
    void manualTriggerOfPoisonJobDoesNot500() throws Exception {
        chaosJobs.setPoison(true);
        try {
            mockMvc.perform(post("/api/scheduler/trigger/poison"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("failed"))
                    .andExpect(jsonPath("$.result").value("failed: 演示异常：远端对账接口 503"));
        } finally {
            chaosJobs.setPoison(false);
        }

        // 开关关掉后，同一个任务恢复 no-op
        mockMvc.perform(post("/api/scheduler/trigger/poison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void errorHandlerSwallowsTaskException() {
        SchedulerErrorHandler handler = new SchedulerErrorHandler();
        assertThatCode(() -> handler.handleError(new IllegalStateException("boom")))
                .doesNotThrowAnyException();
    }

    @Test
    void poisonOffIsNoOp() {
        assertThat(chaosJobs.isPoison()).isFalse();
        // 不抛异常即 no-op
        chaosJobs.runPoisonOnce("manual");
    }
}
