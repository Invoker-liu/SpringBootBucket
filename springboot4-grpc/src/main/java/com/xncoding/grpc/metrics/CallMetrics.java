package com.xncoding.grpc.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** gRPC 调用计数与耗时台账，GET /api/metrics 输出，取值单数据源 */
@Component
public class CallMetrics {

    private final AtomicLong ordersPlaced = new AtomicLong();
    private final AtomicLong notifyCalls = new AtomicLong();
    private final AtomicLong notifyOk = new AtomicLong();
    private final AtomicLong notifyDegrades = new AtomicLong();
    private final AtomicLong deadlineExceeded = new AtomicLong();
    private final AtomicLong getCalls = new AtomicLong();
    private final AtomicLong notFoundMapped = new AtomicLong();
    private final AtomicLong trackCalls = new AtomicLong();
    private final AtomicLong streamEvents = new AtomicLong();
    private final AtomicLong notifyLatencySumMs = new AtomicLong();
    private final AtomicLong lastNotifyLatencyMs = new AtomicLong();

    public long incOrdersPlaced() { return ordersPlaced.incrementAndGet(); }
    public long incNotifyCalls() { return notifyCalls.incrementAndGet(); }
    public long incNotifyOk() { return notifyOk.incrementAndGet(); }
    public long incNotifyDegrades() { return notifyDegrades.incrementAndGet(); }
    public long incDeadlineExceeded() { return deadlineExceeded.incrementAndGet(); }
    public long incGetCalls() { return getCalls.incrementAndGet(); }
    public long incNotFoundMapped() { return notFoundMapped.incrementAndGet(); }
    public long incTrackCalls() { return trackCalls.incrementAndGet(); }
    public void addStreamEvents(int n) { streamEvents.addAndGet(n); }

    public void recordNotifyLatency(long ms) {
        notifyLatencySumMs.addAndGet(ms);
        lastNotifyLatencyMs.set(ms);
    }

    public Snapshot snapshot() {
        long calls = notifyCalls.get();
        return new Snapshot(
                ordersPlaced.get(), calls, notifyOk.get(), notifyDegrades.get(),
                deadlineExceeded.get(), getCalls.get(), notFoundMapped.get(),
                trackCalls.get(), streamEvents.get(),
                calls == 0 ? 0 : notifyLatencySumMs.get() / calls,
                lastNotifyLatencyMs.get());
    }

    public record Snapshot(long ordersPlaced, long notifyCalls, long notifyOk,
                           long notifyDegrades, long deadlineExceeded,
                           long getCalls, long notFoundMapped,
                           long trackCalls, long streamEvents,
                           long avgNotifyLatencyMs, long lastNotifyLatencyMs) {
    }
}
