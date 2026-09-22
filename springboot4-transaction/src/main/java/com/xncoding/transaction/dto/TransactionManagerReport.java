package com.xncoding.transaction.dto;

/**
 * 容器里的事务管理器清单。
 *
 * <p>单独做成一个接口而不是塞进别的响应里，是因为它回答的是一个很具体的问题：
 * 「我这个应用里到底有几个事务管理器，分别叫什么名字」。
 * 多数据源的项目里，这个问题答不上来就意味着某个 {@code @Transactional} 已经失效了。
 *
 * @param count    个数
 * @param managers 形如「transactionManager（JdbcTransactionManager）」的清单
 */
public record TransactionManagerReport(int count, String managers) {
}
