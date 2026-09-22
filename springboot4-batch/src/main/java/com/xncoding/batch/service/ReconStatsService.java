package com.xncoding.batch.service;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对账台账：每次 job 运行的读/写/跳过计数 + 坏行明细。
 * 实现 JobExecutionListener 直接挂到 job 上，afterJob 时落一次快照。
 * 内存态即可，批处理的核心账本在 BATCH_* 元数据表里，这里只是演示侧的观察窗口。
 */
@Service
public class ReconStatsService implements JobExecutionListener {

    /** 坏行记录：行内容 + 跳过原因。 */
    public record SkippedRow(String row, String reason) {}

    public static class RunStats {
        public volatile long jobExecutionId;
        public volatile String jobName;
        public volatile String status;
        public volatile long readCount;
        public volatile long writeCount;
        public volatile long skipCount;
        public volatile long durationMs;
    }

    private final JdbcTemplate jdbcTemplate;

    private final AtomicInteger totalRuns = new AtomicInteger();
    private final AtomicLong totalRead = new AtomicLong();
    private final AtomicLong totalWrite = new AtomicLong();
    private final AtomicLong totalSkipped = new AtomicLong();

    /** 按 jobExecutionId 聚合的坏行明细。 */
    private final Map<Long, List<SkippedRow>> skippedRows = new ConcurrentHashMap<>();

    /** 最近一次运行的快照。 */
    private volatile RunStats lastRun;

    private volatile long currentExecutionId;

    public ReconStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void beginExecution(long jobExecutionId) {
        this.currentExecutionId = jobExecutionId;
    }

    public void onRead() {
        totalRead.incrementAndGet();
    }

    public void onWrite() {
        totalWrite.incrementAndGet();
    }

    public void onSkip(String row, String reason) {
        totalSkipped.incrementAndGet();
        skippedRows.computeIfAbsent(currentExecutionId, k ->
                new java.util.concurrent.CopyOnWriteArrayList<>()).add(
                new SkippedRow(row, reason));
    }

    public void recordRun(JobExecution execution) {        RunStats s = new RunStats();
        s.jobExecutionId = execution.getId();
        s.jobName = execution.getJobInstance().getJobName();
        s.status = execution.getStatus().name();
        s.readCount = execution.getStepExecutions().stream()
                .mapToLong(se -> se.getReadCount() + se.getReadSkipCount()).sum();
        s.writeCount = execution.getStepExecutions().stream()
                .mapToLong(se -> se.getWriteCount()).sum();
        s.skipCount = execution.getStepExecutions().stream()
                .mapToLong(se -> se.getReadSkipCount() + se.getProcessSkipCount() + se.getWriteSkipCount()).sum();
        s.durationMs = execution.getEndTime() == null ? -1
                : java.time.Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis();
        lastRun = s;
        totalRuns.incrementAndGet();
    }

    /** JobExecutionListener：job 一结束就落快照（不管成败）。 */
    @Override
    public void afterJob(JobExecution execution) {
        recordRun(execution);
    }

    public void reset() {
        totalRuns.set(0);
        totalRead.set(0);
        totalWrite.set(0);
        totalSkipped.set(0);
        skippedRows.clear();
        lastRun = null;
    }

    public Map<String, Object> stats() {
        RunStats s = lastRun;
        return Map.ofEntries(
                Map.entry("totalRuns", totalRuns.get()),
                Map.entry("totalRead", totalRead.get()),
                Map.entry("totalWrite", totalWrite.get()),
                Map.entry("totalSkipped", totalSkipped.get()),
                Map.entry("lastRun", s == null ? Map.of() : Map.of(
                        "jobExecutionId", s.jobExecutionId,
                        "jobName", s.jobName == null ? "" : s.jobName,
                        "status", s.status == null ? "" : s.status,
                        "readCount", s.readCount,
                        "writeCount", s.writeCount,
                        "skipCount", s.skipCount,
                        "durationMs", s.durationMs)),
                Map.entry("skippedRows", List.copyOf(
                        skippedRows.values().stream().flatMap(List::stream).toList())));
    }

    /** 从 BATCH_* 元数据表读历史执行（证明账本真的落了库）。 */
    public List<Map<String, Object>> recentExecutions() {
        return jdbcTemplate.queryForList("""
                SELECT JOB_EXECUTION_ID, JOB_INSTANCE_ID, START_TIME, END_TIME, STATUS, EXIT_MESSAGE
                FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 5
                """);
    }
}
