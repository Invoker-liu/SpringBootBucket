package com.xncoding.batch.config;

import javax.sql.DataSource;

import com.xncoding.batch.domain.OrderRow;
import com.xncoding.batch.service.ReconStatsService;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.validator.ValidationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 对账 Job：读 CSV → 校验 → 写 MySQL。
 * Batch 6 写法：老 StepBuilder/TaskletStep 已弃用，主线是 ChunkOrientedStepBuilder；
 * 不写 @EnableBatchProcessing，Boot 自动配置兜底（JobRepository/JobOperator 全给齐）。
 */
@Configuration
public class BatchJobConfig {

    /** job 名，HTTP 触发与 spring.batch.job.name 都用它。 */
    public static final String JOB_NAME = "reconJob";

    /**
     * Step 作用域 reader：每次 job 启动才创建，文件名来自 jobParameters。
     * 注意 @Value 里的 spel 表达式只有 step scope 能解析。
     */
    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    public FlatFileItemReader<OrderRow> reconReader(
            @org.springframework.beans.factory.annotation.Value(
                    "#{jobParameters['fileName']}") String fileName) {
        return buildReader("data/" + fileName);
    }

    static FlatFileItemReader<OrderRow> buildReader(String location) {
        return new FlatFileItemReaderBuilder<OrderRow>()
                .name("reconReader")
                .resource(new ClassPathResource(location))
                .linesToSkip(1)
                .delimited(config -> config.names("orderNo", "merchant", "amount", "status"))
                .targetType(OrderRow.class)
                .strict(true)
                .build();
    }

    /**
     * 校验 processor：金额非负且状态白名单，不合法抛 ValidationException，
     * 配合 faultTolerant().skip() 成为坏行跳过。
     */
    static OrderRow validate(OrderRow row) {
        String bad = null;
        if (row.getOrderNo() == null || row.getOrderNo().isBlank()) {
            bad = "orderNo 缺失";
        } else if (!row.getOrderNo().matches("SO\\d{4,}")) {
            bad = "orderNo 格式非法";
        }
        try {
            if (bad == null && new java.math.BigDecimal(row.getAmount())
                    .compareTo(java.math.BigDecimal.ZERO) <= 0) {
                bad = "金额必须为正";
            }
        } catch (NumberFormatException e) {
            bad = "金额不是数字";
        }
        if (bad == null && !java.util.Set.of("PAID", "REFUND", "SETTLED").contains(row.getStatus())) {
            bad = "状态不在白名单";
        }
        if (bad != null) {
            throw new ValidationException("坏行 [" + row + "]: " + bad);
        }
        return row;
    }

    @Bean
    public JdbcBatchItemWriter<OrderRow> reconWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<OrderRow>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO recon_order
                          (order_no, merchant, amount, status, recon_time)
                        VALUES (:orderNo, :merchant, :amount, :status, NOW())
                        """)
                .beanMapped()
                .assertUpdates(true)
                .build();
    }

    @Bean
    public Job reconJob(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        FlatFileItemReader<OrderRow> reader,
                        JdbcBatchItemWriter<OrderRow> writer,
                        ReconStatsService stats) {
        var step = new ChunkOrientedStepBuilder<OrderRow, OrderRow>(
                        "reconStep", jobRepository, 4)
                .reader(reader)
                .processor(row -> {
                    stats.onRead();
                    return validate(row);
                })
                .writer(items -> {
                    writer.write(items);
                    items.forEach(i -> stats.onWrite());
                })
                .transactionManager(transactionManager)
                .faultTolerant()
                .skip(ValidationException.class, org.springframework.batch.infrastructure.item.file.FlatFileParseException.class)
                .skipLimit(10)
                .skipListener(new ReconSkipListener(stats))
                .build();

        return new org.springframework.batch.core.job.builder.JobBuilder(
                        JOB_NAME, jobRepository)
                .start(step)
                .listener(stats)
                .build();
    }

    /**
     * 坏行落台账：读错、写错、处理错都会到这。
     * 同时实现 StepExecutionListener，在 step 开始时把 jobExecutionId 交给台账。
     */
    static class ReconSkipListener implements
            org.springframework.batch.core.listener.SkipListener<OrderRow, OrderRow>,
            org.springframework.batch.core.listener.StepExecutionListener {

        private final ReconStatsService stats;

        ReconSkipListener(ReconStatsService stats) {
            this.stats = stats;
        }

        @Override
        public void beforeStep(org.springframework.batch.core.step.StepExecution stepExecution) {
            stats.beginExecution(stepExecution.getJobExecutionId());
        }

        @Override
        public void onSkipInRead(Throwable t) {
            stats.onSkip("(解析失败行)", t.getMessage());
        }

        @Override
        public void onSkipInProcess(OrderRow item, Throwable t) {
            stats.onSkip(item.toString(), t.getMessage());
        }

        @Override
        public void onSkipInWrite(OrderRow item, Throwable t) {
            stats.onSkip(item.toString(), t.getMessage());
        }
    }
}
