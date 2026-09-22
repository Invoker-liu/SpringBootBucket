package com.xncoding.resilience.exception;

/**
 * 模拟下游支付通道不可用的业务异常。
 * @Retryable 的 includes/excludes 会把抛出的异常与其嵌套 cause 一起做类型匹配。
 */
public class ChannelUnavailableException extends RuntimeException {

    public ChannelUnavailableException(String message) {
        super(message);
    }
}
