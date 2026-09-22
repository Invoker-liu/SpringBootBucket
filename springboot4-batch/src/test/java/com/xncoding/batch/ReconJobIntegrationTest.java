package com.xncoding.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 对账 job 集成测试：真连 MySQL（springboot4_batch 库）。
 * <p>
 * Batch 6 测试姿势：@SpringBatchTest 提供 JobOperatorTestUtils / JobRepositoryTestUtils；
 * 注意 JobLauncherTestUtils 与 launchJob() 已标记弃用，主线是 JobOperatorTestUtils.startJob()。
 * 每个用例前清 BATCH_* 元数据与业务表，保证可重复执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.batch.test.context.SpringBatchTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconJobIntegrationTest {

    @Autowired
    private JobOperatorTestUtils launcher;

    @Autowired
    private JobRepositoryTestUtils repositoryUtils;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private JobParameters params(String file) {
        return new JobParametersBuilder()
                .addString("fileName", file)
                .toJobParameters();
    }

    @BeforeEach
    void cleanUp() {
        repositoryUtils.removeJobExecutions();
        jdbcTemplate.update("DELETE FROM recon_order");
    }

    @Test
    @Order(1)
    void jobReadsValidatesWritesAndAccounts() throws Exception {
        JobExecution execution = launcher.startJob(params("recon-test-ok.csv"));

        assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
        // 5 行好数据全部入库
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recon_order", Integer.class);
        assertThat(rows).isEqualTo(5);
        // 写计数从元数据表核对（BATCH_STEP_EXECUTION 是批处理自己的账本）
        Long writeCount = jdbcTemplate.queryForObject(
                "SELECT SUM(WRITE_COUNT) FROM BATCH_STEP_EXECUTION"
                        + " WHERE JOB_EXECUTION_ID = " + execution.getId(),
                Long.class);
        assertThat(writeCount).isEqualTo(5L);
    }

    @Test
    @Order(2)
    void badRowsAreSkippedAndJobStillCompletes() throws Exception {
        JobExecution execution = launcher.startJob(params("recon-test-bad.csv"));

        // 3 条坏行（非法状态/负金额/非数字）被 skip，job 不失败
        assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
        Long skipCount = jdbcTemplate.queryForObject(
                "SELECT SUM(READ_SKIP_COUNT) + SUM(PROCESS_SKIP_COUNT)"
                        + " + SUM(WRITE_SKIP_COUNT) FROM BATCH_STEP_EXECUTION"
                        + " WHERE JOB_EXECUTION_ID = " + execution.getId(),
                Long.class);
        assertThat(skipCount).isEqualTo(3L);
        // 好行照常入库
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recon_order", Integer.class);
        assertThat(rows).isEqualTo(5);
    }

    @Test
    @Order(3)
    void sameParametersCannotRunTwice() throws Exception {
        launcher.startJob(params("recon-test-ok.csv"));

        // 同参数 = 同一个 JobInstance，Batch 拒绝重复执行（幂等语义的根）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> launcher.startJob(params("recon-test-ok.csv")))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @Order(4)
    void httpTriggerQueryAndIdempotentRejection() throws Exception {
        // 触发：201 + Location
        String location = mockMvc.perform(post("/api/jobs/recon/run")
                        .param("fileName", "recon-test-ok.csv"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andReturn().getResponse().getHeader("Location");
        long execId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // 元数据表可查（MySQL 列名以大写返回）
        mockMvc.perform(get("/api/jobs/recon/executions/" + execId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.STATUS").value("COMPLETED"));

        // 同文件再触发：409 RFC 9457
        mockMvc.perform(post("/api/jobs/recon/run")
                        .param("fileName", "recon-test-ok.csv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:business-rule-violation"))
                .andExpect(jsonPath("$.title").value("业务规则不满足"));

        // 不存在的 execution：404
        mockMvc.perform(get("/api/jobs/recon/executions/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"));

        // 台账面板
        mockMvc.perform(get("/api/jobs/recon/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.lastRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recentExecutions.length()").value(1));

        // 入库结果与清理
        mockMvc.perform(get("/api/jobs/recon/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        mockMvc.perform(delete("/api/jobs/recon/state"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void interfacesWereMergedInBatch6() {
        // Batch 6：JobRepository 兼 JobExplorer、JobOperator 兼 JobLauncher，
        // 一个 bean 顶过去四个，注入面小了一半
        assertThat(jobRepository).isInstanceOf(JobExplorer.class);
        assertThat(jobOperator).isNotNull();
    }
}
