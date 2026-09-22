package com.xncoding.async.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * void 方法抛出的异常落在这里。Boot 默认实现记一条 ERROR 日志；
 * 换成自己的处理器之后按 WARN 记、按次数计，应用日志保持 ERROR 0 行。
 */
@Component
public class UncaughtCounter implements AsyncUncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UncaughtCounter.class);

    private final AtomicLong count = new AtomicLong();

    private final com.xncoding.async.ledger.TaskLedger ledger;

    public UncaughtCounter(com.xncoding.async.ledger.TaskLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        count.incrementAndGet();
        String taskId = params.length > 0 && params[0] instanceof String s ? s : null;
        boolean tracked = taskId != null && ledger.fail(taskId, "void-exception", ex);
        log.warn("ASYNC_UNCAUGHT method={} taskId={} tracked={} error={}",
                method.getName(), taskId, tracked, ex.toString());
    }

    public long count() {
        return count.get();
    }
}
