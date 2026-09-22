package com.xncoding.echarts.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单日订单统计：date 用 LocalDate（Jackson 3 默认输出 ISO 字符串 2026-09-13），
 * amount 用 BigDecimal（原样输出数值），字段名直接对齐 ECharts xAxis.data / series.data 的取数形状。
 */
public record DailyStat(LocalDate date, long orderCount, BigDecimal amount) {
}
