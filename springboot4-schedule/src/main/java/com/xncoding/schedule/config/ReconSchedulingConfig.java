package com.xncoding.schedule.config;

import com.xncoding.schedule.job.ReconJob;
import com.xncoding.schedule.scheduling.DynamicCronTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 对账扫描任务的注册方式：SchedulingConfigurer + TriggerTask。
 *
 * 注解的 cron 在编译期写死，这里把 cron 收进 DynamicCronTrigger bean，
 * HTTP 接口改值后下一次调度立即生效。taskScheduler bean（SchedulerConfig
 * 声明的线程池）会被框架自动接上，不需要在这里再设一次。
 */
@Configuration
public class ReconSchedulingConfig implements SchedulingConfigurer {

    public static final String DEFAULT_CRON = "0/5 * * * * *";

    private final ReconJob reconJob;

    public ReconSchedulingConfig(ReconJob reconJob) {
        this.reconJob = reconJob;
    }

    @Bean
    public DynamicCronTrigger reconCronTrigger() {
        return new DynamicCronTrigger(DEFAULT_CRON);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                () -> reconJob.runScanOnce("cron"),
                reconCronTrigger());
    }
}
