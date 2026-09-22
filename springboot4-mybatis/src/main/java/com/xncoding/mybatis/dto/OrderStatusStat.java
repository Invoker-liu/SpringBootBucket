package com.xncoding.mybatis.dto;

import com.xncoding.mybatis.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * 按状态聚合的统计结果，由自定义 XML 查询直接映射而来。
 * <p>
 * 写成可变 POJO 而不是 record：XML 里的 {@code resultType} 走的是
 * 无参构造 + setter 的映射方式，POJO 最省事（用 record 得配构造器映射或 resultMap）。
 */
public class OrderStatusStat {

    /** 列名 status -> 枚举，MyBatis 默认按 name() 匹配 */
    private OrderStatus status;

    /** 列名 order_count -> 驼峰属性 orderCount，靠 map-underscore-to-camel-case 打开 */
    private Long orderCount;

    /** 列名 total_amount -> 驼峰属性 totalAmount */
    private BigDecimal totalAmount;

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Long orderCount) {
        this.orderCount = orderCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    /** 展示用的中文状态名 */
    public String getStatusLabel() {
        return status == null ? null : status.getLabel();
    }
}
