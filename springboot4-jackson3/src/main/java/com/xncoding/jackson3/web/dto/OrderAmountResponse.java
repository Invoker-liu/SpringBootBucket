package com.xncoding.jackson3.web.dto;

import com.xncoding.jackson3.domain.Order;
import com.xncoding.jackson3.web.serialize.CentsToYuanSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 金额视图：amountCents 贴 @JsonSerialize(using = CentsToYuanSerializer.class)，
 * 对外输出元；discountYuan 同规则，可空（null 时按默认行为输出）。
 */
public class OrderAmountResponse {

    private Long id;
    private String orderNo;

    @JsonSerialize(using = CentsToYuanSerializer.class)
    private long amountYuan;

    @JsonSerialize(using = CentsToYuanSerializer.class)
    private Long discountYuan;

    public static OrderAmountResponse of(Order o) {
        OrderAmountResponse dto = new OrderAmountResponse();
        dto.id = o.getId();
        dto.orderNo = o.getOrderNo();
        dto.amountYuan = o.getAmountCents();
        dto.discountYuan = o.getDiscountCents();
        return dto;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public long getAmountYuan() { return amountYuan; }
    public Long getDiscountYuan() { return discountYuan; }
}
