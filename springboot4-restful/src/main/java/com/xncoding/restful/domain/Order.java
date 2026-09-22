package com.xncoding.restful.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单领域模型。
 * <p>
 * 使用不可变 record 承载：仓储层以整对象替换的方式实现更新，
 * 避免共享可变状态在并发读写下出现"半个订单"的中间态。
 *
 * @param id            主键，未落库时为 null
 * @param orderNo       订单号，业务唯一键
 * @param customerName  客户姓名
 * @param customerPhone 客户手机号
 * @param totalAmount   订单金额
 * @param status        订单状态
 * @param remark        备注
 * @param createdAt     创建时间
 * @param updatedAt     最后更新时间
 */
public record Order(
        Long id,
        String orderNo,
        String customerName,
        String customerPhone,
        BigDecimal totalAmount,
        OrderStatus status,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 返回一个已更新业务字段的新实例，状态与创建时间保持不变。
     */
    public Order withBusinessFields(String customerName, String customerPhone,
                                    BigDecimal totalAmount, String remark, Instant updatedAt) {
        return new Order(id, orderNo, customerName, customerPhone, totalAmount, status, remark,
                createdAt, updatedAt);
    }

    /**
     * 返回一个状态已流转的新实例。
     */
    public Order withStatus(OrderStatus newStatus, Instant updatedAt) {
        return new Order(id, orderNo, customerName, customerPhone, totalAmount, newStatus, remark,
                createdAt, updatedAt);
    }

    /**
     * 返回一个已分配主键的新实例。
     */
    public Order withId(Long newId) {
        return new Order(newId, orderNo, customerName, customerPhone, totalAmount, status, remark,
                createdAt, updatedAt);
    }
}
