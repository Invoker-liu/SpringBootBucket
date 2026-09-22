package com.xncoding.schedule.service;

import com.xncoding.schedule.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 日报表统计。
 */
@Service
public class ReportService {

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 汇总当前订单并落当日报表（演示按全量统计，生产按 T-1 一天窗口）。 */
    @Transactional
    public Map<String, Object> buildDailyReport() {
        Map<String, Object> summary = jdbc.sql(
                        "SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS total_amount " +
                        "FROM po_order")
                .query()
                .singleRow();
        int count = ((Number) summary.get("order_count")).intValue();
        java.math.BigDecimal total = new java.math.BigDecimal(
                String.valueOf(summary.get("total_amount")));
        jdbc.sql("INSERT INTO daily_report (stat_date, order_count, total_amount, built_at) " +
                        "VALUES (?, ?, ?, NOW(3)) " +
                        "ON DUPLICATE KEY UPDATE order_count = VALUES(order_count), " +
                        "total_amount = VALUES(total_amount), built_at = VALUES(built_at)")
                .param(LocalDate.now()).param(count).param(total).update();
        return Map.of("statDate", LocalDate.now().toString(),
                "orderCount", count, "totalAmount", total);
    }

    public Map<String, Object> today() {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT DATE_FORMAT(stat_date, '%Y-%m-%d') AS stat_date, " +
                        "order_count, total_amount, built_at FROM daily_report WHERE stat_date = ?")
                .param(LocalDate.now())
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("report",
                    LocalDate.now().toString(), "当日报表还没生成");
        }
        return rows.get(0);
    }
}
