package com.xncoding.jpa.dto;

import com.xncoding.jpa.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * 按状态的聚合统计。
 * <p>
 * 这个 record 有第二重身份：它是 {@code OrderRepository.statByStatus()} 里 JPQL
 * 构造器表达式的目标。所以构造器参数顺序必须和 JPQL 里 {@code select new ...} 的
 * 实参顺序严格一致，写反了编译期不报错、运行期才抛异常。
 *
 * @param count      该状态的订单数
 * @param totalAmount 该状态的订单金额合计，<b>没有数据时是 0 而不是 null</b>——
 *                    靠 JPQL 里的 {@code coalesce} 保证
 */
public record OrderStatusStat(
        OrderStatus status,
        Long count,
        BigDecimal totalAmount
) {

    public String getStatusLabel() {
        return status.getLabel();
    }
}
