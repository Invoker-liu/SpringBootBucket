package com.xncoding.multisource.exception;

/**
 * 路由数据源找不到目标库时抛出。
 *
 * <p>{@code AbstractRoutingDataSource} 自己抛的是 {@link IllegalStateException}，
 * 太笼统了 —— 同一个异常类型在别处也会出现，异常处理器不敢乱接。
 * 这里在 {@code DynamicDataSource} 里把它翻译成专用类型，
 * 好处是全局异常处理器可以放心地只接这一个，不至于把别的
 * {@code IllegalStateException} 也误判成「数据源配置错了」。
 */
public class DataSourceRoutingException extends RuntimeException {

    public DataSourceRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
