package com.xncoding.restclient.service;

import com.xncoding.restclient.api.LogisticsApi;
import com.xncoding.restclient.api.RiskApi;
import com.xncoding.restclient.metrics.CallMetrics;
import com.xncoding.restclient.model.RiskRequest;
import com.xncoding.restclient.model.RiskResult;
import com.xncoding.restclient.model.Waybill;
import com.xncoding.restclient.model.WaybillRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RiskApi riskApi;
    private final LogisticsApi logisticsApi;
    private final CallMetrics metrics;

    private final Map<String, PlacedOrder> orders = new ConcurrentHashMap<>();

    public OrderService(RiskApi riskApi, LogisticsApi logisticsApi, CallMetrics metrics) {
        this.riskApi = riskApi;
        this.logisticsApi = logisticsApi;
        this.metrics = metrics;
    }

    public PlacedOrder placeOrder(String orderNo, BigDecimal amount, int itemCount) {
        long t0 = System.nanoTime();

        RiskDecision decision = checkRisk(orderNo, amount);
        Waybill waybill = createWaybill(orderNo, itemCount);

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        PlacedOrder order = new PlacedOrder(orderNo, amount, decision, waybill,
                decision.riskLatencyMs(), waybill == null ? -1 : lastWaybillLatency(), totalMs);
        orders.put(orderNo, order);
        metrics.incOrdersPlaced();
        log.info("ORDER_PLACED orderNo={} decision={} waybill={} totalMs={}",
                orderNo, decision.decision(), waybill == null ? "none" : waybill.waybillNo(), totalMs);
        return order;
    }

    public PlacedOrder get(String orderNo) {
        PlacedOrder order = orders.get(orderNo);
        if (order == null) {
            return null;
        }
        return order;
    }

    /** 查运单最新状态：走 @GetExchange 路线，失败不抛出，返回 UNKNOWN */
    public Waybill waybillStatus(String waybillNo) {
        try {
            return logisticsApi.getWaybill(waybillNo);
        } catch (Exception e) {
            log.warn("WAYBILL_STATUS_FALLBACK waybillNo={} cause={}", waybillNo, e.getClass().getSimpleName());
            return new Waybill(waybillNo, "unknown", "UNKNOWN");
        }
    }

    private RiskDecision checkRisk(String orderNo, BigDecimal amount) {
        metrics.incRiskCalls();
        long t0 = System.nanoTime();
        try {
            RiskResult result = riskApi.check(new RiskRequest(orderNo, amount));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            metrics.recordRiskLatency(ms);
            metrics.incRiskPass();
            return new RiskDecision("PASS", result.reason(), ms);
        } catch (ResourceAccessException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            metrics.recordRiskLatency(ms);
            metrics.incRiskTimeoutDegrades();
            log.warn("RISK_DEGRADE orderNo={} type=timeout cause={} elapsedMs={}",
                    orderNo, e.getCause() == null ? "n/a" : e.getCause().getClass().getSimpleName(), ms);
            return new RiskDecision("DEGRADE_TIMEOUT", "risk service timeout", ms);
        } catch (RestClientResponseException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            metrics.recordRiskLatency(ms);
            metrics.incRiskErrorDegrades();
            log.warn("RISK_DEGRADE orderNo={} type=status status={} elapsedMs={}",
                    orderNo, e.getStatusCode().value(), ms);
            return new RiskDecision("DEGRADE_ERROR", "risk service returned " + e.getStatusCode().value(), ms);
        }
    }

    private Waybill createWaybill(String orderNo, int itemCount) {
        metrics.incWaybillCalls();
        long t0 = System.nanoTime();
        try {
            Waybill waybill = logisticsApi.createWaybill(new WaybillRequest(orderNo, itemCount));
            metrics.recordWaybillLatency((System.nanoTime() - t0) / 1_000_000);
            return waybill;
        } catch (Exception e) {
            metrics.incWaybillErrors();
            log.warn("WAYBILL_FAIL orderNo={} cause={}", orderNo, e.getClass().getSimpleName());
            return null;
        }
    }

    private long lastWaybillLatency() {
        return metrics.snapshot().lastWaybillLatencyMs();
    }

    public record RiskDecision(String decision, String reason, long riskLatencyMs) {
    }

    public record PlacedOrder(String orderNo, BigDecimal amount, RiskDecision risk,
                              Waybill waybill, long riskLatencyMs, long waybillLatencyMs,
                              long totalMs) {
    }

    /** JDK HttpClient 的超时异常族，包装成显式类型便于分流 */
    static final class ResourceAccessExceptionFamily extends RuntimeException {
    }

    public Map<String, PlacedOrder> all() {
        return Map.copyOf(orders);
    }
}
