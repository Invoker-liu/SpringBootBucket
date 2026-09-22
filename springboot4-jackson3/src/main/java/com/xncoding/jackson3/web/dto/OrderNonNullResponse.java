package com.xncoding.jackson3.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xncoding.jackson3.domain.Order;

/**
 * 类级 @JsonInclude(NON_NULL)：整类所有 null 字段都不输出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderNonNullResponse {

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

    public static OrderNonNullResponse of(Order o) {
        OrderNonNullResponse dto = new OrderNonNullResponse();
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
