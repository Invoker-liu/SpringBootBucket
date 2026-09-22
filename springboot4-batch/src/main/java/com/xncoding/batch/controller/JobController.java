package com.xncoding.batch.controller;

import com.xncoding.batch.config.BatchJobConfig;
import com.xncoding.batch.service.ReconStatsService;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对账任务的 HTTP 面板：触发、查状态、看台账、看入库结果。
 */
@RestController
@RequestMapping("/api/jobs/recon")
public class JobController {

    private final JobOperator jobOperator;
    private final Job reconJob;
    private final ReconStatsService stats;
    private final JdbcTemplate jdbcTemplate;

    public JobController(JobOperator jobOperator, Job reconJob,
                         ReconStatsService stats, JdbcTemplate jdbcTemplate) {
        this.jobOperator = jobOperator;
        this.reconJob = reconJob;
        this.stats = stats;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 触发一次对账。同 fileName 重复触发 = 同一 JobInstance，第二次直接 409：
     * 这就是批处理的幂等语义，靠 BATCH_JOB_INSTANCE 表保证。
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestParam String fileName) {
        JobParameters params = new JobParametersBuilder()
                .addString("fileName", fileName)
                .toJobParameters();
        try {
            var execution = jobOperator.start(reconJob, params);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}").buildAndExpand(execution.getId()).toUri();
            return ResponseEntity.created(location).body(Map.of(
                    "jobExecutionId", execution.getId(),
                    "status", execution.getStatus().name(),
                    "fileName", fileName));
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new com.xncoding.batch.exception.BusinessException(
                    HttpStatus.CONFLICT,
                    "该文件已对账完成（同参数的 JobInstance 已存在），重复触发被拒绝");
        } catch (JobExecutionAlreadyRunningException e) {
            throw new com.xncoding.batch.exception.BusinessException(
                    HttpStatus.CONFLICT, "上一次对账还在跑，稍后再试");
        } catch (Exception e) {
            throw new com.xncoding.batch.exception.BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "触发失败: " + e.getMessage());
        }
    }

    /** 查一次执行的终态（读 BATCH_JOB_EXECUTION 元数据表）。 */
    @GetMapping("/executions/{id}")
    public Map<String, Object> execution(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT JOB_EXECUTION_ID, STATUS, START_TIME, END_TIME"
                        + " FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", id);
        if (rows.isEmpty()) {
            throw new com.xncoding.batch.exception.ResourceNotFoundException(
                    "job execution", String.valueOf(id),
                    "job execution %d 不存在".formatted(id));
        }
        return rows.get(0);
    }

    /** 台账：累计读写跳过计数 + 坏行明细 + 元数据表里最近 5 次执行。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("summary", stats.stats(), "recentExecutions", stats.recentExecutions());
    }

    /** 入库结果。 */
    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return jdbcTemplate.queryForList(
                "SELECT id, order_no, merchant, amount, status, recon_time"
                        + " FROM recon_order ORDER BY id");
    }

    /** 测试与演示用：清台账 + 清业务表。 */
    @DeleteMapping("/state")
    public Map<String, Object> reset() {
        stats.reset();
        jdbcTemplate.update("DELETE FROM recon_order");
        return Map.of("reset", true, "at", LocalDateTime.now().toString());
    }
}
