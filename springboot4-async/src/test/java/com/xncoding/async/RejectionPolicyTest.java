package com.xncoding.async;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒绝策略：core 2 / max 4 / queue 2 的有界池灌 10 个任务，
 * 前 2 个占核心、2 个进队列、2 个扩容到 max，剩下 4 个被拒绝——确定性断言。
 */
@SpringBootTest
class RejectionPolicyTest {

    @Autowired
    @Qualifier("boundedExecutor")
    private ThreadPoolTaskExecutor boundedExecutor;

    @Autowired
    private com.xncoding.async.stats.RejectionCounter rejectionCounter;

    @Test
    void boundedPoolRejectsWhenSaturated() {
        long rejectedBefore = rejectionCounter.count();
        AtomicInteger completed = new AtomicInteger();

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            try {
                boundedExecutor.execute(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    completed.incrementAndGet();
                });
                accepted++;
            } catch (TaskRejectedException e) {
                rejected++;
            }
        }

        // 提交动作瞬间完成，池里的分配是确定的：core 2 + queue 2 + 扩容 2 = 接受 6，拒绝 4
        assertThat(accepted).isEqualTo(6);
        assertThat(rejected).isEqualTo(4);
        assertThat(rejectionCounter.count()).isEqualTo(rejectedBefore + 4);

        // 等池里的 6 个任务跑完，日志与后续验证不受残留线程干扰
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> completed.get() == 6);
    }
}
