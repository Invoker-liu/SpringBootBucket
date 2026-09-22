package com.xncoding.jackson3.domain;

import java.time.LocalDateTime;

/**
 * 订单领域模型。可空字段：customerPhone、couponCode、remark、discountCents。
 * 领域层对序列化一无所知，空值策略全部表达在 DTO 层。
 */
public class Order {

    private final Long id;
    private final String orderNo;
    private final String customerName;
    private final String customerPhone;
    private final String couponCode;
    private final String remark;
    private final int quantity;
    private final long amountCents;
    private final Long discountCents;
    private final String status;
    private final LocalDateTime createdAt;

    public Order(Long id, String orderNo, String customerName, String customerPhone,
                 String couponCode, String remark, int quantity, long amountCents,
                 Long discountCents, String status, LocalDateTime createdAt) {
        this.id = id;
        this.orderNo = orderNo;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.couponCode = couponCode;
        this.remark = remark;
        this.quantity = quantity;
        this.amountCents = amountCents;
        this.discountCents = discountCents;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getCustomerName() { return customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public String getCouponCode() { return couponCode; }
    public String getRemark() { return remark; }
    public int getQuantity() { return quantity; }
    public long getAmountCents() { return amountCents; }
    public Long getDiscountCents() { return discountCents; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
