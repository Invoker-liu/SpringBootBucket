package com.xncoding.restful.repository;

import com.xncoding.restful.domain.Order;
import com.xncoding.restful.domain.OrderPageQuery;
import com.xncoding.restful.domain.PageSlice;

import java.util.Optional;

/**
 * 订单仓储接口。
 * <p>
 * 本工程只给出内存实现，目的是让 RESTful 主题不依赖任何外部中间件即可运行。
 * 后续 MyBatis / JPA / MongoDB 主题会提供各自的实现类，接口签名保持不变。
 */
public interface OrderRepository {

    /**
     * 保存订单：id 为 null 时分配主键并返回新实例，否则按 id 覆盖。
     */
    Order save(Order order);

    Optional<Order> findById(Long id);

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    PageSlice<Order> findPage(OrderPageQuery query);

    boolean deleteById(Long id);
}
