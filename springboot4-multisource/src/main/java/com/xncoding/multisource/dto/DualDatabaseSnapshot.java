package com.xncoding.multisource.dto;

/**
 * 一次请求同时读两个库的结果。
 *
 * <p>用来展示静态方案下「两个库各查各的」长什么样 ——
 * 一次 HTTP 请求内部，两次查询落在两个不同的数据库上，
 * 而且它们各自用着自己事务管理器。
 *
 * @param posDatabase 交易库连接报出来的库名
 * @param orderCount  交易库的订单数
 * @param bizDatabase 运营库连接报出来的库名
 * @param productCount 运营库的商品数
 */
public record DualDatabaseSnapshot(
        String posDatabase,
        int orderCount,
        String bizDatabase,
        int productCount) {
}
