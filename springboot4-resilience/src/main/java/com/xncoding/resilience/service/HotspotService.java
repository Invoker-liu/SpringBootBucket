package com.xncoding.resilience.service;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高并发热点服务：两个方法分别演示 REJECT 与 BLOCK 两种限流语义。
 *
 * <p>limit=2 表示同一时刻最多 2 个线程在目标方法体内；
 * REJECT 策略在满员时立刻抛 InvocationRejectedException（它继承
 * java.util.concurrent.RejectedExecutionException），BLOCK 策略排队等待。
 * inFlight 记录方法体内并发线程数，实测峰值不会超过 limit。
 */
@Service
public class HotspotService {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** REJECT 策略：满员即拒绝。方法体耗时 200ms，方便与并发请求形成重叠。 */
    @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    public String rejectReport(int seq) {
        return enterBody(seq, 200);
    }

    /** BLOCK 策略：满员排队，等前面的线程退出再进。 */
    @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.BLOCK)
    public String blockReport(int seq) {
        return enterBody(seq, 200);
    }

    private String enterBody(int seq, long costMs) {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try {
            Thread.sleep(costMs);
            return "seq-" + seq + "-done";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public int getMaxInFlight() {
        return maxInFlight.get();
    }
}
