package com.xncoding.cache.dto;

/**
 * 缓存效果统计：数据库真实查询次数。用它证明「第二次调用没有走到方法体」。
 */
public record StatsResponse(long dbHits, long orderCount) {
}
