package com.xncoding.mongo.repository;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * 摘要投影仓储。
 * <p>
 * 和 {@link OrderRepository} 一样都是 {@code MongoRepository<Order, String>}，
 * 区别只在于方法的<b>返回类型</b>声明成了接口 {@link OrderSummary}。
 * Spring Data 一看返回类型不是实体，就切到投影模式：
 * 只把 {@code OrderSummary} 里声明的那四个字段查回来，其余一律不下发。
 * <p>
 * 这正是上一篇（JPA）用过的同一套机制——Spring Data Commons 提供的接口投影
 * 对 JPA、MongoDB、Redis 各模块是通用的，所以在数据存储之间换的时候，
 * 这一层代码可以原样搬。投影是本系列里为数不多的"跨存储完全可移植"的能力。
 */
public interface OrderSummaryRepository extends MongoRepository<Order, String> {

    List<OrderSummary> findByStatus(OrderStatus status);
}
