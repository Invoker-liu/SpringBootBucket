package com.xncoding.aop.web;

import com.xncoding.aop.store.OrderStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维观测接口：审计行的查询与清空、订单库存计数。
 * 这些方法刻意不标 @OperationLog，避免观测动作自己给自己写审计。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final JdbcTemplate jdbc;
    private final OrderStore store;

    public OpsController(JdbcTemplate jdbc, OrderStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @PostMapping("/audit-clear")
    public Map<String, Object> clearAudit() {
        int removed = jdbc.update("DELETE FROM t_audit_log");
        return Map.of("removed", removed);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> recentAudit(@RequestParam(defaultValue = "10") int limit) {
        return jdbc.queryForList(
                "SELECT id, module, action, method, args, status, error, cost_ms, created_at "
                        + "FROM t_audit_log ORDER BY id DESC LIMIT ?", limit);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Integer auditRows = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Integer.class);
        Long errorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE status = 'ERROR'", Long.class);
        return Map.of(
                "auditRows", auditRows == null ? 0 : auditRows,
                "auditErrorRows", errorRows == null ? 0 : errorRows,
                "stockLeft", store.stockLeft(),
                "orderCount", store.orderCount());
    }
}
