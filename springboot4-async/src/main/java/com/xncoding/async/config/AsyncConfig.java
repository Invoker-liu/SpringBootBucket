package com.xncoding.async.config;

import com.xncoding.async.stats.RejectionCounter;
import com.xncoding.async.stats.UncaughtCounter;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异步总开关与两个线程池。
 *
 * <p>bizExecutor 走属性路：spring.task.execution.* 的值由 ThreadPoolTaskExecutorBuilder
 * 吃进默认值，这里只 build()。boundedExecutor 走手工路：core 2 / max 4 / queue 2 全部
 * 显式声明，拒绝策略先计数再抛，S4 验证要靠这个计数对账。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 默认执行器：builder 的默认值来自 spring.task.execution.*，线程名前缀 biz-。 */
    @Bean
    public ThreadPoolTaskExecutor bizExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }

    /** 有界池：演示队列满 → 扩容到 max → 再满触发拒绝策略的完整链路。 */
    @Bean
    public ThreadPoolTaskExecutor boundedExecutor(RejectionCounter counter) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("bounded-");
        executor.setRejectedExecutionHandler((r, pool) -> {
            counter.increase();
            throw new RejectedExecutionException("boundedExecutor 已满（core=2, max=4, queue=2）");
        });
        executor.initialize();
        return executor;
    }

    /**
     * 自己接管 AsyncConfigurer：默认执行器指向 bizExecutor，void 方法抛出的异常
     * 进自己的计数处理器。声明了这个 bean 之后，Boot 自动装配的
     * applicationTaskExecutorAsyncConfigurer 整体退让（@ConditionalOnMissingBean）。
     */
    @Bean
    public AsyncConfigurer asyncConfigurer(@Qualifier("bizExecutor") Executor bizExecutor,
                                           UncaughtCounter uncaughtCounter) {
        return new AsyncConfigurer() {

            @Override
            public Executor getAsyncExecutor() {
                return bizExecutor;
            }

            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return uncaughtCounter;
            }
        };
    }
}
