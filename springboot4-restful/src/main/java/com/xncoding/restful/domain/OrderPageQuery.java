package com.xncoding.restful.domain;

/**
 * 订单分页查询条件。
 *
 * @param keyword     模糊匹配订单号或客户姓名，可为 null
 * @param status      状态精确过滤，可为 null
 * @param page        页码，从 0 开始
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

    /** 允许排序的字段白名单，防止客户端传入任意属性名导致内部异常 */
    public static final java.util.Set<String> SORTABLE_FIELDS =
            java.util.Set.of("id", "orderNo", "customerName", "totalAmount", "status", "createdAt", "updatedAt");

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
