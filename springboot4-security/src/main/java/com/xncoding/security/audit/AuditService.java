package com.xncoding.security.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String username, String action, String orderNo, String result, String detail) {
        jdbc.update("""
                        INSERT INTO t_audit_log(username, action, order_no, result, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, NOW(3))
                        """,
                username, action, orderNo, result, detail);
    }

    public List<Map<String, Object>> latest(int limit) {
        return jdbc.queryForList("""
                SELECT id, username, action, order_no AS orderNo, result, detail, created_at AS createdAt
                FROM t_audit_log ORDER BY id DESC LIMIT ?
                """, Math.min(limit, 200));
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Integer.class);
        return n == null ? 0 : n;
    }

    public void clear() {
        jdbc.update("DELETE FROM t_audit_log");
    }
}
