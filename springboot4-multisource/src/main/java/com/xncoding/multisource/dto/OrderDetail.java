package com.xncoding.multisource.dto;

import com.xncoding.multisource.domain.OrderItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 + 明细的聚合视图。
 *
 * <p>这个查询要 join {@code t_order} 和 {@code t_order_item}。
 * 这两张表都在交易库里，所以它同时证明了两件事：
 * 这次查询确实落在了交易库，而且交易库里两张表都在。
 * 如果数据源切错了，得到的是「表不存在」而不是空结果 —— 报错方向很明确。
 */
public class OrderDetail {

    private Long id;

    private String orderNo;

    private String customerName;

    private BigDecimal totalAmount;

    private String status;

    private LocalDateTime createdAt;

    /** 顺手把当前库名带出来，省得再发一次探针请求。 */
    private String databaseName;

    private List<OrderItem> items;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }
}
