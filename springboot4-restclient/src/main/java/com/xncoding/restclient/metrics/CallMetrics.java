package com.xncoding.restclient.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 调用计数与耗时台账，GET /api/orders/metrics 输出，取值单数据源 */
@Component
public class CallMetrics {

    private final AtomicLong ordersPlaced = new AtomicLong();
    private final AtomicLong riskCalls = new AtomicLong();
    private final AtomicLong riskPass = new AtomicLong();
    private final AtomicLong riskTimeoutDegrades = new AtomicLong();
    private final AtomicLong riskErrorDegrades = new AtomicLong();
    private final AtomicLong waybillCalls = new AtomicLong();
    private final AtomicLong waybillErrors = new AtomicLong();
    private final AtomicLong riskLatencySumMs = new AtomicLong();
    private final AtomicLong lastRiskLatencyMs = new AtomicLong();
    private final AtomicLong lastWaybillLatencyMs = new AtomicLong();

    public long incOrdersPlaced() { return ordersPlaced.incrementAndGet(); }
    public long incRiskCalls() { return riskCalls.incrementAndGet(); }
    public long incRiskPass() { return riskPass.incrementAndGet(); }
    public long incRiskTimeoutDegrades() { return riskTimeoutDegrades.incrementAndGet(); }
    public long incRiskErrorDegrades() { return riskErrorDegrades.incrementAndGet(); }
    public long incWaybillCalls() { return waybillCalls.incrementAndGet(); }
    public long incWaybillErrors() { return waybillErrors.incrementAndGet(); }

    public void recordRiskLatency(long ms) {
        riskLatencySumMs.addAndGet(ms);
        lastRiskLatencyMs.set(ms);
    }

    public void recordWaybillLatency(long ms) { lastWaybillLatencyMs.set(ms); }

    public Snapshot snapshot() {
        long calls = riskCalls.get();
        return new Snapshot(
                ordersPlaced.get(), calls, riskPass.get(),
                riskTimeoutDegrades.get(), riskErrorDegrades.get(),
                waybillCalls.get(), waybillErrors.get(),
                calls == 0 ? 0 : riskLatencySumMs.get() / calls,
                lastRiskLatencyMs.get(), lastWaybillLatencyMs.get());
    }

    public record Snapshot(long ordersPlaced, long riskCalls, long riskPass,
                           long riskTimeoutDegrades, long riskErrorDegrades,
                           long waybillCalls, long waybillErrors,
                           long avgRiskLatencyMs, long lastRiskLatencyMs,
                           long lastWaybillLatencyMs) {
    }
}
