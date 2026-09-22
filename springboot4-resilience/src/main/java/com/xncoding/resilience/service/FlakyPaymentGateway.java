package com.xncoding.resilience.service;

import com.xncoding.resilience.exception.ChannelUnavailableException;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟不稳定的下游支付通道。
 *
 * <p>arm(failTimes) 后的前 failTimes 次调用抛 ChannelUnavailableException，
 * 之后恢复正常，用于制造「前两轮失败第三轮成功」的可控抖动。
 * 每次真实的下游调用都会记入 callCount，重试拦截器循环调用它几次
 * 就能得到几次计数，这个数字直接对应文章里的尝试次数。
 */
@Service
public class FlakyPaymentGateway {

    private final AtomicInteger armedFailures = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger txSeq = new AtomicInteger(1000);

    /** 装填失败次数：接下来 failTimes 次调用失败，之后成功。 */
    public void arm(int failTimes) {
        armedFailures.set(Math.max(0, failTimes));
        callCount.set(0);
    }

    public String call(String orderId) {
        callCount.incrementAndGet();
        int remaining = armedFailures.getAndUpdate(v -> v > 0 ? v - 1 : 0);
        if (remaining > 0) {
            throw new ChannelUnavailableException(
                    "支付通道超时, orderId=" + orderId + ", 剩余失败配额=" + (remaining - 1));
        }
        return "txn-" + txSeq.incrementAndGet();
    }

    /** 本次 arm 以来下游被真实调用的总次数（含失败与成功）。 */
    public int getCallCount() {
        return callCount.get();
    }
}
