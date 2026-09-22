package com.xncoding.async.service;

import com.xncoding.async.ledger.TaskLedger;
import com.xncoding.async.ledger.TraceIdHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 下单后的三件慢事 + 四个坑点探针，全部 @Async。
 * once 方法是不带注解的同步版本，同步基线接口和 @Async 方法共用同一段业务代码。
 */
@Component
public class OrderSideEffects {

    private final TaskLedger ledger;

    @Value("${app.chaos.sms-millis:600}")
    private long smsMillis;

    @Value("${app.chaos.points-millis:700}")
    private long pointsMillis;

    @Value("${app.chaos.report-millis:800}")
    private long reportMillis;

    public OrderSideEffects(TaskLedger ledger) {
        this.ledger = ledger;
    }

    // ------------------------------------------------------------ 三件慢事（异步版）

    /** void 返回：异常直接抛给 AsyncUncaughtExceptionHandler。 */
    @Async
    public void sendOrderSms(String taskId, String orderNo) {
        smsOnce(taskId, orderNo);
    }

    /** CompletableFuture 返回：异常留在 future 里，get() 时才浮出来。 */
    @Async
    public CompletableFuture<Integer> creditPoints(String taskId, String orderNo) {
        return CompletableFuture.completedFuture(pointsOnce(taskId, orderNo));
    }

    @Async
    public void aggregateReport(String taskId, String orderNo) {
        reportOnce(taskId, orderNo);
    }

    // ------------------------------------------------------------ 三件慢事（同步版，基线用）

    public void smsOnce(String taskId, String orderNo) {
        TaskLedger.Entry entry = ledger.begin(taskId, "sms");
        sleep(smsMillis);
        ledger.done(taskId, entry);
    }

    public int pointsOnce(String taskId, String orderNo) {
        TaskLedger.Entry entry = ledger.begin(taskId, "points");
        sleep(pointsMillis);
        ledger.done(taskId, entry);
        return 10;
    }

    public void reportOnce(String taskId, String orderNo) {
        TaskLedger.Entry entry = ledger.begin(taskId, "report");
        sleep(reportMillis);
        ledger.done(taskId, entry);
    }

    // ------------------------------------------------------------ 坑点探针

    /** void 方法抛异常：进 AsyncUncaughtExceptionHandler（计数 + 台账标 FAILED）。 */
    @Async
    public void poisonVoid(String taskId) {
        ledger.begin(taskId, "void-exception");
        sleep(150);
        throw new IllegalStateException("演示异常：短信通道 503");
    }

    /** CompletableFuture 方法抛异常：进不了 Uncaught 处理器，异常待在 future 里。 */
    @Async
    public CompletableFuture<Integer> poisonFuture(String taskId) {
        TaskLedger.Entry entry = ledger.begin(taskId, "future-exception");
        sleep(150);
        IllegalStateException failure = new IllegalStateException("演示异常：积分服务超时");
        ledger.fail(taskId, entry, failure);
        throw failure;
    }

    /** 异步线程里读 ThreadLocal：HTTP 线程写入的值在这里是 null。 */
    @Async
    public CompletableFuture<String> readTraceId(String taskId) {
        TaskLedger.Entry entry = ledger.begin(taskId, "thread-local");
        sleep(100);
        String value = TraceIdHolder.get();
        ledger.done(taskId, entry);
        return CompletableFuture.completedFuture(value);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
