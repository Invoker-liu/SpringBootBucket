package com.xncoding.mongo.domain;

import java.util.Set;

/**
 * 分页查询条件。
 *
 * @param sortBy     排序字段，必须是<b>实体的属性名</b>（不是 {@code @Field} 映射后的存储名）
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
     * 可排序字段白名单，写的是<b>Java 属性名</b>。
     * <p>
     * 白名单仍然是必须的，但理由和上一篇（JPA）不太一样，值得分辨：
     * <ul>
     *   <li>JPA 那边，{@code Sort.by} 收到不认识的属性会直接抛
     *       {@code PropertyReferenceException}，写 {@code created_at} 会当场炸。
     *       所以白名单主要是为了"把 500 变成回退到默认排序"。</li>
     *   <li>MongoDB 这边不一样：Mongo 允许按任意路径排序，{@code Sort.by("created_at")}
     *       不会被拒绝，会原样拼进 {@code sort} 段。而 {@code created_at}
     *       恰好<b>就是</b>文档里真实的字段名，于是它还真能排出正确结果。</li>
     * </ul>
     * 也就是说，用属性名 {@code createdAt} 和用存储名 {@code created_at} 在本篇里<b>都能跑</b>。
     * 正因为都能跑，才更要白名单统一口径：两种写法混进同一个接口，
     * 哪天给实体加个 {@code @Field} 改名，用存储名的那个调用方会静默排错。
     * <p>
     * 注意 {@code id} 不在白名单里。文档里的主键叫 {@code _id}，
     * 而 {@code _id} 是 ObjectId，按它排序等价于按"生成顺序"排，
     * 在分片或跨进程写入时不具备时间含义，容易误导调用方。要时间序就排 {@code createdAt}。
     */
    private static final Set<String> SORTABLE =
            Set.of("orderNo", "customerName", "totalAmount", "status", "createdAt", "updatedAt");

    public static final String DEFAULT_SORT_FIELD = "createdAt";

    /** 不合法就回退到默认字段，不抛异常——排序字段写错不该让整个查询失败 */
    public OrderPageQuery {
        if (sortBy == null || !SORTABLE.contains(sortBy)) {
            sortBy = DEFAULT_SORT_FIELD;
        }
    }
}
