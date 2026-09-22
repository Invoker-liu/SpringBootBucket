package com.xncoding.async.controller;

import com.xncoding.async.exception.BusinessException;
import com.xncoding.async.ledger.TaskLedger;
import com.xncoding.async.ledger.TraceIdHolder;
import com.xncoding.async.service.FutureRegistry;
import com.xncoding.async.service.OrderSideEffects;
import com.xncoding.async.service.TrapService;
import com.xncoding.async.stats.RejectionCounter;
import com.xncoding.async.stats.UncaughtCounter;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步探针：自调用失效、异常去处、ThreadLocal 断层、拒绝策略、任务台账查询，
 * 全部走 HTTP，S4 验证脚本的每一个取值都从这里拿到。
 */
@RestController
@RequestMapping("/api")
public class AsyncProbeController {

    private final OrderSideEffects sideEffects;
    private final TrapService trapService;
    private final FutureRegistry futureRegistry;
    private final TaskLedger ledger;
    private final UncaughtCounter uncaughtCounter;
    private final RejectionCounter rejectionCounter;
    private final ThreadPoolTaskExecutor bizExecutor;
    private final ThreadPoolTaskExecutor boundedExecutor;

    public AsyncProbeController(OrderSideEffects sideEffects, TrapService trapService,
                                FutureRegistry futureRegistry, TaskLedger ledger,
                                UncaughtCounter uncaughtCounter, RejectionCounter rejectionCounter,
                                ThreadPoolTaskExecutor bizExecutor, ThreadPoolTaskExecutor boundedExecutor) {
        this.sideEffects = sideEffects;
        this.trapService = trapService;
        this.futureRegistry = futureRegistry;
        this.ledger = ledger;
        this.uncaughtCounter = uncaughtCounter;
        this.rejectionCounter = rejectionCounter;
        this.bizExecutor = bizExecutor;
        this.boundedExecutor = boundedExecutor;
    }

    // ------------------------------------------------------------ 任务台账

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> task(@PathVariable String taskId) {
        if (!ledger.exists(taskId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("status", ledger.aggregate(taskId));
        List<TaskLedger.Entry> entries = ledger.snapshot(taskId);
        body.put("entries", entries);
        CompletableFuture<Integer> future = futureRegistry.get(taskId);
        if (future != null && future.isDone()) {
            try {
                body.put("points", future.get());
            } catch (ExecutionException e) {
                // CompletableFuture 路径的异常待在 future 里，get() 这一刻才浮出来
                body.put("futureException", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return body;
    }

    // ------------------------------------------------------------ 坑点探针

    /** this 自调用 vs 代理调用：线程名直接暴露谁走了代理。 */
    @PostMapping("/probe/self-invocation")
    public Map<String, String> selfInvocation() {
        return trapService.probeSelfInvocation();
    }

    /** void 方法抛异常：进 AsyncUncaughtExceptionHandler，计数 +1、台账标 FAILED。 */
    @PostMapping("/probe/void-exception")
    public Map<String, Object> voidException() {
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        sideEffects.poisonVoid(taskId);
        return Map.of("taskId", taskId, "kind", "void-exception");
    }

    /** CompletableFuture 方法抛异常：不进 Uncaught 处理器，GET /api/tasks/{taskId} 里看 futureException。 */
    @PostMapping("/probe/future-exception")
    public Map<String, Object> futureException() {
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        futureRegistry.put(taskId, sideEffects.poisonFuture(taskId));
        return Map.of("taskId", taskId, "kind", "future-exception");
    }

    /** ThreadLocal 断层：HTTP 线程写的 traceId，异步线程读到 null。 */
    @PostMapping("/probe/thread-local")
    public Map<String, String> threadLocal() {
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        String traceId = "trace-" + UUID.randomUUID().toString().substring(0, 8);
        String httpThread = Thread.currentThread().getName();
        TraceIdHolder.set(traceId);
        try {
            CompletableFuture<String> future = sideEffects.readTraceId(taskId);
            String asyncValue;
            try {
                asyncValue = future.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                asyncValue = "interrupted";
            } catch (ExecutionException | TimeoutException e) {
                asyncValue = "error: " + e;
            }
            String asyncThread = trapService.awaitThread(taskId, "thread-local", 3000);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("httpThread", httpThread);
            body.put("httpValue", traceId);
            body.put("asyncThread", asyncThread);
            body.put("asyncValue", asyncValue);
            return body;
        } finally {
            TraceIdHolder.clear();
        }
    }

    /** 拒绝策略：向有界池灌 tasks 个慢任务，数出接受几个、拒绝几个。 */
    @PostMapping("/probe/rejection")
    public Map<String, Object> rejection(@RequestParam(defaultValue = "10") int tasks) {
        int total = Math.min(Math.max(tasks, 1), 50);
        int accepted = 0;
        for (int i = 0; i < total; i++) {
            try {
                boundedExecutor.execute(() -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                accepted++;
            } catch (TaskRejectedException e) {
                // 拒绝计数在自定义 RejectedExecutionHandler 里累加
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", total);
        body.put("accepted", accepted);
        body.put("rejected", total - accepted);
        body.put("rejectionCounter", rejectionCounter.count());
        return body;
    }

    // ------------------------------------------------------------ 池状态与计数

    @GetMapping("/probe/counters")
    public Map<String, Long> counters() {
        return Map.of("uncaughtHandled", uncaughtCounter.count(),
                "rejected", rejectionCounter.count());
    }

    @GetMapping("/probe/stats")
    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizExecutor", poolStats(bizExecutor));
        body.put("boundedExecutor", poolStats(boundedExecutor));
        body.put("uncaughtHandled", uncaughtCounter.count());
        body.put("rejected", rejectionCounter.count());
        return body;
    }

    private Map<String, Object> poolStats(ThreadPoolTaskExecutor executor) {
        var pool = executor.getThreadPoolExecutor();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("threadNamePrefix", executor.getThreadNamePrefix());
        stats.put("corePoolSize", executor.getCorePoolSize());
        stats.put("maxPoolSize", executor.getMaxPoolSize());
        stats.put("poolSize", pool.getPoolSize());
        stats.put("activeCount", pool.getActiveCount());
        stats.put("queueSize", pool.getQueue().size());
        stats.put("completedTaskCount", pool.getCompletedTaskCount());
        return stats;
    }
}
