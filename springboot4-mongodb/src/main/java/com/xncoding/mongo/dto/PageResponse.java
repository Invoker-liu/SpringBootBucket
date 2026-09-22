package com.xncoding.mongo.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应体。
 * <p>
 * 刻意不把 Spring Data 的 {@code Page} 直接当响应返回。它的 JSON 结构长这样：
 * 内容在一个 {@code content} 数组里，同时混着 {@code pageable}、{@code sort}
 * 这些嵌套对象，还有一个 4.x 新加的 {@code pageable.mode} 字段。
 * 这些是实现细节，不该变成对外契约。分页是跨篇统一的对外格式，
 * 不能跟着某个框架的类走——前两篇也是同样的处理。
 * <p>
 * 另外 Spring Data 的页码从 0 开始，而 {@code PageResponse} 的 {@code page}
 * 直接透传这个 0 基页码，不擅自加一：对外约定统一从 0 开始。
 * <p>
 * 一个 MongoDB 特有的细节：{@code Page} 的 {@code totalElements} 在分页查询里
 * 是<b>额外发一条 count 命令</b>算出来的。想要极致性能可以用
 * {@code MongoTemplate} 的 {@code Query.limit()} + 游标翻页来省掉这一趟，
 * 代价是没有总数。本篇按前三篇的统一契约走，保留总数。
 *
 * @param list       当前页数据
 * @param page       当前页码，从 0 开始
 * @param size       每页条数
 * @param total      总条数
 * @param totalPages 总页数
 */
public record PageResponse<T>(
        List<T> list,
        int page,
        int size,
        long total,
        int totalPages
) {

    public static <S, T> PageResponse<T> of(Page<S> source, Function<S, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
