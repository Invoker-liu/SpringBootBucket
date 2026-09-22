package com.xncoding.jpa.repository;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderStatus;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 只读仓储，专供列表页的摘要查询。
 * <p>
 * 它继承的是最朴素的 {@link Repository} 而不是 {@link org.springframework.data.jpa.repository.JpaRepository}，
 * 所以只会暴露出下面这两个方法，没有 {@code save}、没有 {@code delete}。
 * 这比"继承一堆方法然后约定大家别乱用"要可靠——接口上没有，就是调不出来。
 * <p>
 * 返回类型是接口投影 {@link OrderSummary}，生成的 SQL 只 select 用到的四列。
 * <p>
 * 为什么另开一个仓储而不是塞进 {@code OrderRepository}：同一个方法名
 * {@code findByStatus} 没法既返回 {@code List<Order>} 又返回
 * {@code List<OrderSummary>}，返回类型不同算不上重载，Java 直接编译不过。
 */
public interface OrderSummaryRepository extends Repository<Order, Long> {

    List<OrderSummary> findByStatus(OrderStatus status);
}
