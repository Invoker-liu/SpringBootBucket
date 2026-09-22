package com.xncoding.testing.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Order insert(Order order) {
        org.springframework.jdbc.support.KeyHolder keys =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("INSERT INTO orders(order_no, amount, status, created_at) VALUES(?, ?, ?, ?)")
                .param(order.orderNo())
                .param(order.amount())
                .param(order.status().name())
                .param(Timestamp.from(order.createdAt()))
                .update(keys);
        return new Order(keys.getKey().longValue(), order.orderNo(), order.amount(),
                order.status(), order.createdAt());
    }

    public Optional<Order> findById(Long id) {
        return jdbc.sql("SELECT id, order_no, amount, status, created_at FROM orders WHERE id = ?")
                .param(id)
                .query((rs, i) -> new Order(rs.getLong("id"), rs.getString("order_no"),
                        rs.getBigDecimal("amount"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public List<Order> findAll(OrderStatus status) {
        String sql = "SELECT id, order_no, amount, status, created_at FROM orders";
        if (status != null) {
            sql += " WHERE status = ?";
        }
        sql += " ORDER BY id";
        var spec = jdbc.sql(sql);
        if (status != null) {
            spec = spec.param(status.name());
        }
        return spec.query((rs, i) -> new Order(rs.getLong("id"), rs.getString("order_no"),
                rs.getBigDecimal("amount"), OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant())).list();
    }

    public boolean existsByOrderNo(String orderNo) {
        return jdbc.sql("SELECT COUNT(1) FROM orders WHERE order_no = ?")
                .param(orderNo)
                .query((rs, i) -> rs.getInt(1) > 0)
                .single();
    }

    public int updateStatus(Long id, OrderStatus status) {
        return jdbc.sql("UPDATE orders SET status = ? WHERE id = ?")
                .param(status.name())
                .param(id)
                .update();
    }

    public boolean deleteById(Long id) {
        return jdbc.sql("DELETE FROM orders WHERE id = ?")
                .param(id)
                .update() > 0;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(1) FROM orders")
                .query((rs, i) -> rs.getLong(1))
                .single();
    }
}
