package com.xncoding.jpa.repository;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * 动态条件构造。
 * <p>
 * 列表页的筛选条件是"可选"的：有关键词就加上、没有就不加。用 JPQL 拼字符串或者
 * 写一堆 {@code if} 分支都不好看，Specification 的用法是把每个条件写成一个
 * 独立的小函数，最后按需拼起来。
 * <p>
 * 每个方法返回的都是一段"还没执行的谓词"，真正拼成 SQL 是在调用
 * {@code findAll(spec, pageable)} 的时候。
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    /**
     * 关键词模糊匹配订单号或客户名。
     * <p>
     * 两边都套 {@code lower()}，是为了让匹配不受大小写影响。订单号是大写字母加数字，
     * 客户名可能是中文；中文本来没有大小写，{@code lower()} 对它无害，
     * 所以一条条件就能同时覆盖两种输入。
     * <p>
     * {@code like} 的第三个参数可以指定转义字符，这里没用——如果关键词里出现
     * {@code %} 或 {@code _}，它们会被当成通配符。要彻底处理得先转义再拼。
     */
    public static Specification<Order> keywordContains(String keyword) {
        return (root, query, cb) -> {
            String pattern = "%" + keyword.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("orderNo")), pattern),
                    cb.like(cb.lower(root.get("customerName")), pattern));
        };
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> amountAtLeast(BigDecimal minAmount) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("totalAmount"), minAmount);
    }

    /**
     * 把若干条件用 AND 组合起来，null 的自动忽略。
     * <p>
     * {@code Specification.allOf(...)} 是 4.x 提供的新写法，等价于旧版一路
     * {@code .and()} 链下去，但为空时返回的是"永真"条件而不是 null，
     * 省掉了每处调用都要判空。
     */
    @SafeVarargs
    public static Specification<Order> allOfNullable(Specification<Order>... specs) {
        return Specification.allOf(java.util.Arrays.stream(specs)
                .filter(java.util.Objects::nonNull)
                .toList());
    }
}
