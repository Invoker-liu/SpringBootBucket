package com.xncoding.aop.exception;

/**
 * 幂等拒绝：同一指纹的请求在窗口内第二次到达。
 * 由 IdempotentAspect 抛出，全局异常处理器映射成 RFC 9457 的 409。
 */
public class IdempotentRejectedException extends RuntimeException {

    private final int windowSeconds;

    public IdempotentRejectedException(int windowSeconds) {
        super("相同请求在 %d 秒内已受理，请勿重复提交".formatted(windowSeconds));
        this.windowSeconds = windowSeconds;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }
}
