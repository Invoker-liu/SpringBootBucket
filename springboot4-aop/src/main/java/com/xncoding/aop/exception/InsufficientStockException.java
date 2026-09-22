package com.xncoding.aop.exception;

/** 库存不足。业务方法抛出，穿透全部切面后由全局异常处理器渲染成 422。 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(int requested, int left) {
        super("库存不足：需要 %d 件，剩余 %d 件".formatted(requested, left));
    }
}
