package com.xncoding.jpa.repository;

import com.xncoding.jpa.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * 订单摘要投影，只取列表页要用到的四个字段。
 * <p>
 * 接口投影是 Spring Data 的原生能力：方法名对应实体的 getter，返回值由框架生成的代理实现。
 * 好处很直接——生成的 SQL 只 {@code select} 这四列，不会把 {@code remark}、
 * 时间戳这些用不上的字段也读出来，也不会去碰 {@code items} 那个关联集合。
 * <p>
 * 用 record 也可以（构造器表达式），但接口投影不需要在实体里补构造器，
 * 也不用写 JPQL，加一个接口就能用。
 */
public interface OrderSummary {

    Long getId();

    String getOrderNo();

    BigDecimal getTotalAmount();

    OrderStatus getStatus();
}
