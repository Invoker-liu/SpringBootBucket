package com.xncoding.mybatis.domain;

import java.util.Set;

/**
 * 订单分页查询条件。
 * <p>
 * 与上一篇同名类保持一致：对外契约不变，只是底层实现从内存 Map 换成了 MySQL。
 *
 * @param keyword     模糊匹配订单号或客户姓名，可为 null
 * @param status      状态精确过滤，可为 null
 * @param page        页码，从 0 开始（对外契约用 0 基，转换见 OrderService）
 * @param size        每页条数
 * @param sortBy      排序字段（白名单内的领域字段名）
 * @param descending  是否倒序
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
     * 允许排序的字段白名单。
     * <p>
     * 排序字段最终要落到 SQL 的 ORDER BY 上，而 ORDER BY 的列名没法用占位符参数化。
     * 所以必须在这里收口成固定几个值，绝不能把客户端传来的字符串直接拼进 SQL。
     */
    public static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "orderNo", "customerName", "totalAmount", "status", "createdAt", "updatedAt");

    public static final String DEFAULT_SORT_FIELD = "createdAt";
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    public OrderPageQuery {
        if (sortBy == null || !SORTABLE_FIELDS.contains(sortBy)) {
            sortBy = DEFAULT_SORT_FIELD;
        }
        size = Math.clamp(size, 1, MAX_SIZE);
        page = Math.max(page, 0);
    }
}
