package com.xncoding.mongo.repository;

import com.xncoding.mongo.domain.OrderStatus;

/**
 * 订单摘要的接口投影。
 * <p>
 * <b>这是一个接口，不是类。</b>Spring Data 在运行时给它生成代理：
 * 只有声明了 getter 的字段才会出现在下发的 {@code find} 的投影里，
 * 其余字段一个都不查。和上面 {@code findByStatusWithFields} 那种手写 {@code fields}
 * 的写法相比，好处是<b>类型安全</b>——字段名不再是字符串，
 * 改实体属性名的时候 IDE 的重命名能跟着一起改，不会留下一个查不到的旧字段名。
 * <p>
 * 也不是没有代价：投影是"封闭"的，这里声明了什么就只能有什么，
 * 想让投影结果里出现计算字段得用 {@code @Value} 表达式（SpEL）。
 * 本篇不涉及，需要计算就直接投影完在内存里算。
 */
public interface OrderSummary {

    String getId();

    String getOrderNo();

    String getCustomerName();

    OrderStatus getStatus();
}
