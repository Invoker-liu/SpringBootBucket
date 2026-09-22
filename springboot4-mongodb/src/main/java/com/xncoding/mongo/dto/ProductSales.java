package com.xncoding.mongo.dto;

import java.math.BigDecimal;

/**
 * 商品维度的销量统计（聚合 {@code $unwind} 之后的结果）。
 *
 * @param productName 商品名
 * @param totalQuantity 总件数
 * @param salesAmount   销售额合计
 */
public record ProductSales(
        String productName,
        long totalQuantity,
        BigDecimal salesAmount
) {
}
