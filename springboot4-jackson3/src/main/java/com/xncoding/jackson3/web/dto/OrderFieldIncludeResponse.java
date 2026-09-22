package com.xncoding.jackson3.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xncoding.jackson3.domain.Order;

/**
 * 字段级 @JsonInclude(NON_NULL)：只对贴了注解的 couponCode 生效，
 * 其余 null 字段照默认行为输出。
 */
public class OrderFieldIncludeResponse {

    private Long id;
    private String orderNo;
    private String customerName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String couponCode;

    private String remark;
    private int quantity;
    private long amountCents;
    private Long discountCents;
    private String status;

    public static OrderFieldIncludeResponse of(Order o) {
        OrderFieldIncludeResponse dto = new OrderFieldIncludeResponse();
        dto.id = o.getId();
        dto.orderNo = o.getOrderNo();
        dto.customerName = o.getCustomerName();
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
    public String getCouponCode() { return couponCode; }
    public String getRemark() { return remark; }
    public int getQuantity() { return quantity; }
    public long getAmountCents() { return amountCents; }
    public Long getDiscountCents() { return discountCents; }
    public String getStatus() { return status; }
}
