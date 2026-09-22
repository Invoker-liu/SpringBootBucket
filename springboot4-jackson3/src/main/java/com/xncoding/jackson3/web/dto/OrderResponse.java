package com.xncoding.jackson3.web.dto;

import com.xncoding.jackson3.domain.Order;

/**
 * 不贴任何注解的 DTO，用来实测 Jackson 3 对 null 字段的默认输出行为。
 */
public class OrderResponse {

    private Long id;
    private String orderNo;
    private String customerName;
    private String customerPhone;
    private String couponCode;
    private String remark;
    private int quantity;
    private long amountCents;
    private Long discountCents;
    private String status;

    public static OrderResponse of(Order o) {
        OrderResponse dto = new OrderResponse();
        dto.id = o.getId();
        dto.orderNo = o.getOrderNo();
        dto.customerName = o.getCustomerName();
        dto.customerPhone = o.getCustomerPhone();
        dto.couponCode = o.getCouponCode();
        dto.remark = o.getRemark();
        dto.quantity = o.getQuantity();
        dto.amountCents = o.getAmountCents();
        dto.discountCents = o.getDiscountCents();
        dto.status = o.getStatus();
        return dto;
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
}
