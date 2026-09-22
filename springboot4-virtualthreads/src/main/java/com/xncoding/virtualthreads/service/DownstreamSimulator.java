package com.xncoding.virtualthreads.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 下游服务模拟器：用 Thread.sleep 模拟一次 IO 密集型远程调用（RPC / HTTP）。
 * sleep 与 socket 阻塞在虚拟线程上的行为一致：阻塞期间虚拟线程卸载、让出载体线程。
 * 平台线程档下，sleep 期间请求线程被完整占用；虚拟线程档下仅占用一个廉价的虚拟线程。
 */
@Service
public class DownstreamSimulator {

    private static final Logger log = LoggerFactory.getLogger(DownstreamSimulator.class);

    /** 模拟调用一个下游并返回结果，附带当前执行线程信息，用于验证线程模型。 */
    public DownstreamResult call(String downstream, long delayMs) {
        long start = System.nanoTime();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟调用被中断", e);
        }
        long costMs = (System.nanoTime() - start) / 1_000_000;
        Thread current = Thread.currentThread();
        log.debug("downstream={} cost={}ms thread={}", downstream, costMs, current);
        return new DownstreamResult(downstream, costMs, current.toString());
    }

    /**
     * 单次下游调用结果：名称、耗时、执行线程的 Thread.toString()。
     * 虚拟线程形如 VirtualThread[#67,tomcat-handler-3]/runnable@ForkJoinPool-1-worker-2，
     * 平台线程形如 Thread[#49,http-nio-18270-exec-8,5,main]。
     */
    public record DownstreamResult(String downstream, long costMs, String thread) {
    }
}
