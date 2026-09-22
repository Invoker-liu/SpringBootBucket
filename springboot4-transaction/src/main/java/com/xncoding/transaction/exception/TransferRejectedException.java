package com.xncoding.transaction.exception;

/**
 * 转账被拒。【受检异常】，故意不用 RuntimeException 继承。
 *
 * <p>这个类的存在本身就是本篇第一个坑的主角：Spring 的默认回滚规则只认
 * {@code RuntimeException} 和 {@code Error}，受检异常抛出去，事务照样提交。
 * 所以它一定会被读者亲眼看到「抛了异常但钱还是扣了」。
 */
public class TransferRejectedException extends Exception {

    public TransferRejectedException(String message) {
        super(message);
    }

    /** 带原因。受检异常在业务代码里经常是「包装一个下层异常再抛」，没有这个构造器就没法保留 cause 链。 */
    public TransferRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
