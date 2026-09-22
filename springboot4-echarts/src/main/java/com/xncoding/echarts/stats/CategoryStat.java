package com.xncoding.echarts.stats;

import java.math.BigDecimal;

/**
 * 类目维度统计：category 作 ECharts 饼图/柱图的类目轴数据，amount 汇总金额。
 */
public record CategoryStat(String category, long orderCount, BigDecimal amount) {
}
