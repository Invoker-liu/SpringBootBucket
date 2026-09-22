package com.xncoding.apiversion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1 响应体：完整订单快照，字段一平铺。
 */
public class OrderResponseV1 {

    private Long id;
    private String orderNo;
    private String customerName;
    private String customerPhone;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal discount;
    private BigDecimal shippingFee;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime createdAt;

    public static OrderResponseV1 of(Long id, String orderNo, String customerName, String customerPhone,
                                     BigDecimal unitPrice, Integer quantity, BigDecimal discount,
                                     BigDecimal shippingFee, BigDecimal totalAmount,
                                     String status, LocalDateTime createdAt) {
        OrderResponseV1 v = new OrderResponseV1();
        v.id = id;
        v.orderNo = orderNo;
        v.customerName = customerName;
        v.customerPhone = customerPhone;
        v.unitPrice = unitPrice;
        v.quantity = quantity;
        v.discount = discount;
        v.shippingFee = shippingFee;
        v.totalAmount = totalAmount;
        v.status = status;
        v.createdAt = createdAt;
        return v;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
