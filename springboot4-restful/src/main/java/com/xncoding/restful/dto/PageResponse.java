package com.xncoding.restful.dto;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应体。
 * <p>
 * 刻意不使用 Spring Data 的 {@code Page} 直接作为响应：那会把
 * {@code pageable}/{@code sort}/{@code first}/{@code last} 等十几个内部字段暴露成对外契约，
 * 一旦升级 Spring Data 就会破坏接口兼容性。
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
