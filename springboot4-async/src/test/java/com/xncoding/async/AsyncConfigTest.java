package com.xncoding.async;

import com.xncoding.async.config.AsyncConfig;
import com.xncoding.async.stats.UncaughtCounter;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置事实钉在测试里：属性值进了 builder 建的池、
 * Boot 的默认执行器因 Executor bean 出现而退让、AsyncConfigurer 接线正确。
 */
@SpringBootTest
class AsyncConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("bizExecutor")
    private ThreadPoolTaskExecutor bizExecutor;

    @Autowired
    @Qualifier("boundedExecutor")
    private ThreadPoolTaskExecutor boundedExecutor;

    @Autowired
    private AsyncConfigurer asyncConfigurer;

    @Test
    void bizExecutorInheritsSpringTaskExecutionProperties() {
        // application.yml 里 spring.task.execution.pool.core-size=4、thread-name-prefix=biz-
        // 值经 ThreadPoolTaskExecutorBuilder 流进来，属性路生效的实证
        assertThat(bizExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(bizExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(bizExecutor.getMaxPoolSize()).isEqualTo(8);
        assertThat(bizExecutor.getThreadNamePrefix()).isEqualTo("biz-");
    }

    @Test
    void boundedExecutorIsFullyManual() {
        assertThat(boundedExecutor.getCorePoolSize()).isEqualTo(2);
        assertThat(boundedExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(boundedExecutor.getThreadNamePrefix()).isEqualTo("bounded-");
    }

    @Test
    void applicationTaskExecutorBacksOffWhenExecutorBeansExist() {
        // 任何 Executor bean 一出现，Boot 的 applicationTaskExecutor 整套默认装配退让
        assertThat(context.containsBean("applicationTaskExecutor")).isFalse();
        assertThat(context.containsBean("applicationTaskExecutorAsyncConfigurer")).isFalse();
    }

    @Test
    void asyncConfigurerWiresBizExecutorAndCustomHandler() {
        assertThat(asyncConfigurer.getAsyncExecutor()).isSameAs(bizExecutor);
        AsyncUncaughtExceptionHandler handler = asyncConfigurer.getAsyncUncaughtExceptionHandler();
        assertThat(handler).isInstanceOf(UncaughtCounter.class);
    }

    @Test
    void asyncConfigClassCarriesEnableAsync() {
        // 防呆：谁把 @EnableAsync 摘掉，全部 @Async 静默失效，这里第一个报
        assertThat(AsyncConfig.class.isAnnotationPresent(
                org.springframework.scheduling.annotation.EnableAsync.class)).isTrue();
    }
}
