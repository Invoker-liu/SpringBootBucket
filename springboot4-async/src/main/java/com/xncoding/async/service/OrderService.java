package com.xncoding.async.service;

import com.xncoding.async.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 下单服务：同一个下单动作提供异步与同步两条执行路径，
 * 差异只在三件慢事是并行投出去还是串行做完。
 */
@Service
public class OrderService {

    private final JdbcClient jdbc;
    private final OrderSideEffects sideEffects;
    private final FutureRegistry futureRegistry;

    public OrderService(JdbcClient jdbc, OrderSideEffects sideEffects, FutureRegistry futureRegistry) {
        this.jdbc = jdbc;
        this.sideEffects = sideEffects;
        this.futureRegistry = futureRegistry;
    }

    public record PlaceResult(String taskId, long elapsedMillis) {
    }

    /** 异步下单：订单落库后把三件慢事并行投出去，立刻返回。 */
    public PlaceResult placeOrder(String orderNo, BigDecimal amount) {
        long start = System.nanoTime();
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        insert(orderNo, amount, "ASYNC");

        sideEffects.sendOrderSms(taskId, orderNo);
        futureRegistry.put(taskId, sideEffects.creditPoints(taskId, orderNo));
        sideEffects.aggregateReport(taskId, orderNo);

        return new PlaceResult(taskId, (System.nanoTime() - start) / 1_000_000);
    }

    /** 同步下单：同样的三件慢事串行做完才返回，这就是拖死下单接口的写法。 */
    public PlaceResult placeOrderSync(String orderNo, BigDecimal amount) {
        long start = System.nanoTime();
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        insert(orderNo, amount, "SYNC");

        sideEffects.smsOnce(taskId, orderNo);
        sideEffects.pointsOnce(taskId, orderNo);
        sideEffects.reportOnce(taskId, orderNo);

        return new PlaceResult(taskId, (System.nanoTime() - start) / 1_000_000);
    }

    private void insert(String orderNo, BigDecimal amount, String mode) {
        jdbc.sql("INSERT INTO po_order (order_no, amount, exec_mode) VALUES (?, ?, ?)")
                .param(orderNo).param(amount).param(mode)
                .update();
    }

    public Map<String, Object> findByOrderNo(String orderNo) {
        List<Map<String, Object>> rows = jdbc
                .sql("SELECT order_no, amount, exec_mode, created_at FROM po_order WHERE order_no = ?")
                .param(orderNo)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("order", orderNo);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_no", row.get("order_no"));
        body.put("amount", row.get("amount"));
        body.put("exec_mode", row.get("exec_mode"));
        body.put("created_at", String.valueOf(row.get("created_at")));
        return body;
    }
}
