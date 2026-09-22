package com.xncoding.amqp.domain;

import java.time.Instant;

/**
 * 订单事件：跨服务传递的唯一契约。
 *
 * <p>普通类而不是 record：给后续加字段留余地，且反序列化行为最直白。
 * Jackson 3 对两种都支持，团队契约类建议保持"无参构造 + getter/setter"的保守形态。
 */
public class OrderEvent {

    /** 事件类型：订单已创建 / 订单已支付 */
    public static final String TYPE_ORDER_CREATED = "ORDER_CREATED";
    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    /** 演示用毒丸事件：永远消费失败，用于观察重试与死信 */
    public static final String TYPE_POISON = "POISON";

    private String eventId;
    private String orderNo;
    private String type;
    private String product;
    private Instant occurredAt;

    public OrderEvent() {
    }

    public OrderEvent(String eventId, String orderNo, String type, String product, Instant occurredAt) {
        this.eventId = eventId;
        this.orderNo = orderNo;
        this.type = type;
        this.product = product;
        this.occurredAt = occurredAt;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    @Override
    public String toString() {
        return "OrderEvent{eventId='" + eventId + "', orderNo='" + orderNo
                + "', type='" + type + "', product='" + product + "'}";
    }
}
