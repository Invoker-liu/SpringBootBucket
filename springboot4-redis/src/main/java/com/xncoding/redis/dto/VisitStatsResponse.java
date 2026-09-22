package com.xncoding.redis.dto;

import java.time.LocalDate;

/**
 * 访问统计响应：历史总访问 + 当日去重访客。
 */
public record VisitStatsResponse(
        long totalVisits,
        LocalDate date,
        long todayUniqueVisitors) {
}
