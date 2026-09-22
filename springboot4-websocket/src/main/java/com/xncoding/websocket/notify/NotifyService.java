package com.xncoding.websocket.notify;

import com.xncoding.websocket.ws.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件编排：订单落库后生成新订单事件，经会话登记广播出去，可再定向给指定用户。
 * seq 是事件序号，客户端用它断言收到的消息顺序。
 */
@Service
public class NotifyService {

    private final JdbcClient jdbc;
    private final SessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong();

    public NotifyService(JdbcClient jdbc, SessionRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public record Order(String orderNo, String amount, String status, String createdAt) {
    }

    public Order createOrder(String orderNo, String amount) {
        jdbc.sql("""
                INSERT INTO po_order (order_no, amount, status, created_at)
                VALUES (?, ?, 'CREATED', NOW(3))
                ON DUPLICATE KEY UPDATE amount = VALUES(amount)
                """).param(orderNo).param(amount).update();
        String createdAt = jdbc.sql("SELECT created_at FROM po_order WHERE order_no = ?")
                .param(orderNo).query((rs, i) -> rs.getString(1)).single();
        return new Order(orderNo, amount, "CREATED", createdAt);
    }

    public String orderEvent(Order order) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "order");
        event.put("seq", seq.incrementAndGet());
        event.put("orderNo", order.orderNo());
        event.put("amount", order.amount());
        event.put("status", order.status());
        event.put("ts", Instant.now().toEpochMilli());
        return mapper.writeValueAsString(event);
    }

    public int broadcastNewOrder(Order order) {
        return registry.broadcast(orderEvent(order));
    }

    public Map<String, Object> direct(String user, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "direct");
        payload.put("to", user);
        payload.put("message", message);
        payload.put("ts", Instant.now().toEpochMilli());
        int delivered = registry.sendToUser(user, mapper.writeValueAsString(payload));
        payload.put("delivered", delivered);
        return payload;
    }

    public long eventSeq() {
        return seq.get();
    }
}
