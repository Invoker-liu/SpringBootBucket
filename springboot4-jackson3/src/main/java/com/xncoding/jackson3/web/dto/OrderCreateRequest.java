package com.xncoding.jackson3.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.xncoding.jackson3.web.deserialize.YuanToCentsDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 下单请求体。三个反序列化容错点集中在这一类：
 * couponCode / discountCents 的 null 落成空值（AS_EMPTY），
 * quantity 是 primitive（JSON null 打进 primitive 的实测点），
 * amountYuan 走自定义反序列化器（元转分）。
 */
public class OrderCreateRequest {

    private String customerName;

    /** 商户侧单号，也接受别名 orderNo。 */
    @JsonAlias("orderNo")
    private String merchantOrderNo;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private String couponCode;

    private int quantity;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Long discountCents;

    /** 请求体里的金额按元计（241.82），@JsonProperty 定名 amount，落字段转成分。 */
    @JsonProperty("amount")
    @JsonDeserialize(using = YuanToCentsDeserializer.class)
    private Long amountCents;

    public String getCustomerName() { return customerName; }
    public String getMerchantOrderNo() { return merchantOrderNo; }
    public String getCouponCode() { return couponCode; }
    public int getQuantity() { return quantity; }
    public Long getDiscountCents() { return discountCents; }
    public Long getAmountCents() { return amountCents; }

    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public void setMerchantOrderNo(String merchantOrderNo) { this.merchantOrderNo = merchantOrderNo; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setDiscountCents(Long discountCents) { this.discountCents = discountCents; }
    public void setAmountCents(Long amountCents) { this.amountCents = amountCents; }
}
