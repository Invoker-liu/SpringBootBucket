package com.xncoding.restful.domain;

import java.util.List;

/**
 * 分页结果切片，仅承载"数据 + 总数"，不含排序等实现细节。
 *
 * @param content 当前页数据
 * @param total   满足条件的总条数
 * @param <T>     元素类型
 */
public record PageSlice<T>(List<T> content, long total) {
}
