package com.xncoding.jpa.domain;

import java.util.Set;

/**
 * 分页查询条件。
 *
 * @param sortBy     排序字段，必须是<b>实体的属性名</b>，不是数据库列名
 * @param descending 是否倒序
 */
public record OrderPageQuery(
        String keyword,
        OrderStatus status,
        int page,
        int size,
        String sortBy,
        boolean descending
) {

    /**
     * 可排序字段白名单。
     * <p>
     * <b>这里是 JPA 和 MyBatis-Plus 一个很容易踩的差别</b>：MyBatis-Plus 的
     * {@code OrderItem} 拼的是 SQL 片段，写的是数据库列名（{@code created_at}）；
     * Spring Data 的 {@code Sort} 走的是实体属性路径（{@code createdAt}）。
     * 上一篇的 {@code sort=created_at,desc} 拿到这一篇会直接抛
     * {@code PropertyReferenceException}，报"找不到属性 created_at"。
     * <p>
     * 白名单是必须的：{@code Sort.by} 接的是字符串，随便传什么都会拼进查询，
     * 不校验就等于把实体结构暴露给调用方。
     */
    private static final Set<String> SORTABLE =
            Set.of("id", "orderNo", "customerName", "totalAmount", "status", "createdAt", "updatedAt");

    public static final String DEFAULT_SORT_FIELD = "createdAt";

    /** 不合法就回退到默认字段，不抛异常——排序字段写错不该让整个查询失败 */
    public OrderPageQuery {
        if (sortBy == null || !SORTABLE.contains(sortBy)) {
            sortBy = DEFAULT_SORT_FIELD;
        }
    }
}
