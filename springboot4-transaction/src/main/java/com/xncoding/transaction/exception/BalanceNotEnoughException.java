package com.xncoding.transaction.exception;

/**
 * 余额不足。【运行时异常】，默认就会被回滚。
 *
 * <p>和 {@link TransferRejectedException} 配对使用：一个受检一个运行时，
 * 两个都抛出去，就能看出「默认回滚规则到底认哪一类」。
 */
public class BalanceNotEnoughException extends RuntimeException {

    public BalanceNotEnoughException(String message) {
        super(message);
    }
}
