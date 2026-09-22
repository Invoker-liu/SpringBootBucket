package com.xncoding.multisource.dto;

/**
 * 「事务管理器绑错数据源」这个反例的结果。
 *
 * @param scenario          场景说明
 * @param transactionManager 实际用的事务管理器 bean 名
 * @param writeTarget       这次写的是哪个库
 * @param exceptionMessage  方法里抛出来的异常
 * @param ordersBefore      调用前的订单数
 * @param ordersAfter       调用后的订单数
 * @param rolledBack        订单数有没有回到调用前
 * @param explanation       为什么是这个结果
 */
public record TransactionDemoResult(
        String scenario,
        String transactionManager,
        String writeTarget,
        String exceptionMessage,
        int ordersBefore,
        int ordersAfter,
        boolean rolledBack,
        String explanation) {
}
