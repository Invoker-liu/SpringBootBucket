package com.xncoding.async.stats;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 有界池拒绝策略的计数器：拒绝一次 +1，S4 验证按这个数对账。 */
@Component
public class RejectionCounter {

    private final AtomicLong count = new AtomicLong();

    public void increase() {
        count.incrementAndGet();
    }

    public long count() {
        return count.get();
    }
}
