package com.xncoding.async.ledger;

/**
 * 演示 ThreadLocal 断层用的持有者：HTTP 线程写入的值，
 * 异步线程读不到，除非显式传递或挂 TaskDecorator。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String value) {
        TRACE_ID.set(value);
    }

    public static String get() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
