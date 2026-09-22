package com.xncoding.transaction.dto;

/**
 * 某一时刻的事务状态快照。
 *
 * @param active      当前线程是否真的处在一个事务里（这是判断 {@code @Transactional} 有没有生效的第一依据）
 * @param name        事务名，Spring 默认取「类名.方法名」
 * @param readOnly    是不是只读事务
 * @param isolation   隔离级别；null 表示跟随数据库默认
 * @param database    当前连接连的是哪个库
 * @param connectionId 当前连接的 CONNECTION_ID()
 * @param hasSavepoint 当前事务有没有 savepoint（NESTED 生效的直接证据）
 * @param newTransaction 这个事务是不是刚由本次调用开启的
 * @param rollbackOnly 事务是否已被标记为「只能回滚」
 */
public record TxState(
        boolean active,
        String name,
        boolean readOnly,
        Integer isolation,
        String database,
        Long connectionId,
        boolean hasSavepoint,
        boolean newTransaction,
        boolean rollbackOnly
) {
}
