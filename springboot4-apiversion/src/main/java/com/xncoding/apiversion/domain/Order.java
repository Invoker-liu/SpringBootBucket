package com.xncoding.apiversion.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单领域对象。版本分叉不落在这里：业务层只认这一个模型。
 */
public class Order {

    private Long id;

    private String orderNo;

    private String customerName;

    private String customerPhone;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 数量 */
    private Integer quantity;

    /** 折扣，1.00 表示无折扣 */
    private BigDecimal discount;

    /** 运费 */
    private BigDecimal shippingFee;

    /** 总金额 = 单价 * 数量 * 折扣 + 运费 */
    private BigDecimal totalAmount;

    private String status;

    private LocalDateTime createdAt;

    public Order() {
    }

    public Order(Long id, String orderNo, String customerName, String customerPhone,
                 BigDecimal unitPrice, Integer quantity, BigDecimal discount,
                 BigDecimal shippingFee, String status, LocalDateTime createdAt) {
        this.id = id;
        this.orderNo = orderNo;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.discount = discount;
        this.shippingFee = shippingFee;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity))
                .multiply(discount).add(shippingFee);
        this.status = status;
        this.createdAt = createdAt;
    }

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

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee;
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
}
