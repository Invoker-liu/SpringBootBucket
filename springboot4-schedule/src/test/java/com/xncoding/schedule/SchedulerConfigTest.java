package com.xncoding.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线程池配置实证：手工声明的 taskScheduler 接管了自动配置，
 * 池大小、线程名前缀都以我们的声明为准。
 */
@SpringBootTest(properties = "app.scheduler.pool-size=3")
class SchedulerConfigTest {

    @Autowired
    @Qualifier("taskScheduler")
    private TaskScheduler taskScheduler;

    @Test
    void taskSchedulerIsConfigurableThreadPool() {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(scheduler.getPoolSize()).isEqualTo(3);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("order-sched-");
    }
}
