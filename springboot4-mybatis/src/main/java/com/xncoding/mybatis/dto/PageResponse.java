package com.xncoding.mybatis.dto;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应体。
 * <p>
 * 刻意不把 MyBatis-Plus 的 {@code Page} 直接当响应返回：它会连
 * {@code current}、{@code size}、{@code orders}、{@code optimizeCountSql}、{@code searchCount}
 * 等十几个内部字段一起序列化出来，把实现细节变成了对外契约。
 * 分页是跨篇统一的对外格式，不该跟着某个框架的类走。
 *
 * @param list       当前页数据
 * @param page       当前页码，从 0 开始
 * @param size       每页条数
 * @param total      总条数
 * @param totalPages 总页数
 * @param <T>        元素类型
 */
public record PageResponse<T>(
        List<T> list,
        int page,
        int size,
        long total,
        int totalPages
) {

    public static <S, T> PageResponse<T> of(List<S> source, int page, int size, long total,
                                            Function<S, T> mapper) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(source.stream().map(mapper).toList(), page, size, total, totalPages);
    }
}
