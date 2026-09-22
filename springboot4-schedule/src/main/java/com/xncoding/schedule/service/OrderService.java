package com.xncoding.schedule.service;

import com.xncoding.schedule.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 订单服务：给超时取消任务提供数据。
 */
@Service
public class OrderService {

    private final JdbcClient jdbc;

    public OrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> create(String orderNo, String amount) {
        jdbc.sql("INSERT INTO po_order (order_no, amount) VALUES (?, ?)")
                .param(orderNo).param(amount).update();
        return findByNo(orderNo);
    }

    public Map<String, Object> findByNo(String orderNo) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT order_no, amount, status, created_at, cancel_time " +
                        "FROM po_order WHERE order_no = ?")
                .param(orderNo)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("order", orderNo, "订单不存在: " + orderNo);
        }
        return rows.get(0);
    }

    /** 取消所有超过 timeoutSeconds 仍未支付的订单，返回取消条数。 */
    public int cancelTimeout(int timeoutSeconds) {
        return jdbc.sql("UPDATE po_order SET status = 'CANCELLED', cancel_time = NOW(3) " +
                        "WHERE status = 'PENDING_PAYMENT' AND created_at < NOW(3) - INTERVAL ? SECOND")
                .param(timeoutSeconds)
                .update();
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.sql("SELECT order_no, amount, status, created_at, cancel_time " +
                        "FROM po_order ORDER BY id DESC LIMIT 50")
                .query()
                .listOfRows();
    }
}
