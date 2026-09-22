package com.xncoding.cache.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单领域对象。
 * <p>
 * 刻意写成普通类而不是 record：它要进 Redis 缓存，走 JSON 序列化器的
 * 多态通道（default typing）时，record 是 final 的、不带默认无参构造，
 * 写入的值没有 @class 类型信息，读回来只能退化成 Map（07 篇 Redis 的教训）。
 */
public class Order {

    /** 待支付 */
    public static final String STATUS_CREATED = "CREATED";
    /** 已支付 */
    public static final String STATUS_PAID = "PAID";
    /** 已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String orderNo;
    private String product;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
    private Instant payTime;

    /** Jackson 反序列化需要无参构造 */
    public Order() {
    }

    public Order(String orderNo, String product, BigDecimal amount) {
        this.orderNo = orderNo;
        this.product = product;
        this.amount = amount;
        this.status = STATUS_CREATED;
        this.createdAt = Instant.now();
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPayTime() {
        return payTime;
    }

    public void setPayTime(Instant payTime) {
        this.payTime = payTime;
    }
}
