package com.xncoding.schedule.config;

import com.xncoding.schedule.scheduling.SchedulerErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度线程池配置。
 *
 * Boot 自动装配的 taskScheduler 是单线程池（spring.task.scheduling.pool.size 默认 1），
 * 全部任务串行共用一个线程：一个任务卡住，其余全部停摆。
 * 这里手工声明同名 taskScheduler bean 完全接管，做三件事：
 * 池大小可配、线程名带前缀（日志/执行记录里能看出哪个线程在跑）、
 * 挂自定义 ErrorHandler（任务抛异常时接住，不重试、不中断后续调度）。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${app.scheduler.pool-size:1}") int poolSize,
            @Value("${app.scheduler.thread-name-prefix:order-sched-}") String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        // @Scheduled 方法抛出的异常会流到这里：记录、告警，调度本身不受影响
        scheduler.setErrorHandler(new SchedulerErrorHandler());
        return scheduler;
    }
}
