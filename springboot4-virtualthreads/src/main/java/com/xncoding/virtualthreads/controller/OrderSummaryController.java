package com.xncoding.virtualthreads.controller;

import com.xncoding.virtualthreads.service.DownstreamSimulator;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 订单汇总接口：每个请求串行 / 并行调 3 个下游（模拟可控延迟的慢调用），
 * 响应中携带真实执行线程名，作为两种线程档位的运行时证据。
 */
@RestController
public class OrderSummaryController {

    private final DownstreamSimulator simulator;
    private final Environment environment;

    public OrderSummaryController(DownstreamSimulator simulator, Environment environment) {
        this.simulator = simulator;
        this.environment = environment;
    }

    /**
     * 串行版：请求线程依次阻塞调 3 个下游，总耗时约 3 × delayMs。
     * 这是 IO 密集接口的典型形态，线程大部分时间在等待下游。
     */
    @GetMapping("/api/orders/{id}/summary")
    public OrderSummary summary(@PathVariable String id,
                                @RequestParam(name = "delayMs", defaultValue = "200") long delayMs)
            throws InterruptedException {
        long start = System.nanoTime();
        List<DownstreamSimulator.DownstreamResult> downstreams = new ArrayList<>();
        downstreams.add(simulator.call("inventory", delayMs));
        downstreams.add(simulator.call("pricing", delayMs));
        downstreams.add(simulator.call("logistics", delayMs));
        long costMs = (System.nanoTime() - start) / 1_000_000;
        return new OrderSummary(id, Thread.currentThread().toString(), downstreams, costMs);
    }

    /**
     * 并行版：用 Java 21 的 Executors.newVirtualThreadPerTaskExecutor 把 3 个下游调用
     * 扇出到 3 个虚拟线程并发执行，总耗时约 max(3 × delayMs)，无论开关是否打开。
     */
    @GetMapping("/api/orders/{id}/summary-parallel")
    public OrderSummary summaryParallel(@PathVariable String id,
                                        @RequestParam(name = "delayMs", defaultValue = "200") long delayMs)
            throws Exception {
        long start = System.nanoTime();
        List<String> names = List.of("inventory", "pricing", "logistics");
        List<DownstreamSimulator.DownstreamResult> downstreams = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DownstreamSimulator.DownstreamResult>> futures = names.stream()
                    .map(name -> executor.submit(() -> simulator.call(name, delayMs)))
                    .toList();
            for (Future<DownstreamSimulator.DownstreamResult> future : futures) {
                downstreams.add(future.get());
            }
        }
        long costMs = (System.nanoTime() - start) / 1_000_000;
        return new OrderSummary(id, Thread.currentThread().toString(), downstreams, costMs);
    }

    /**
     * 后台任务示例：Thread.startVirtualThread 起一个虚拟线程异步写审计，
     * 立即返回该线程的描述，证明程序式创建不受开关约束、随时可用。
     */
    @PostMapping("/api/orders/{id}/audit")
    public AuditAccepted audit(@PathVariable String id) throws InterruptedException {
        long start = System.nanoTime();
        // 虚拟线程是守护线程，适合短生命周期任务；join 仅为了让示例能观察到执行线程名
        Thread worker = Thread.ofVirtual().name("audit-" + id).start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        });
        worker.join();
        long costMs = (System.nanoTime() - start) / 1_000_000;
        return new AuditAccepted(id, worker.toString(), costMs, Instant.now().toString());
    }

    /** 运行时线程档位自检：返回开关取值与当前请求线程，供测试与验证脚本断言。 */
    @GetMapping("/api/threads/status")
    public ThreadStatus status() {
        String enabled = environment.getProperty("spring.threads.virtual.enabled", "false");
        return new ThreadStatus(enabled, Thread.currentThread().toString());
    }

    record OrderSummary(String id, String requestThread,
                        List<DownstreamSimulator.DownstreamResult> downstreams, long costMs) {
    }

    record AuditAccepted(String id, String auditThread, long costMs, String at) {
    }

    record ThreadStatus(String virtualEnabled, String requestThread) {
    }
}
