package com.xncoding.async.service;

import com.xncoding.async.ledger.TaskLedger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自调用失效探针：同一个 bean 内部 this 调用走不到代理，
 * 注解静默失效，方法仍在调用者线程同步执行。
 */
@Service
public class TrapService {

    private final TaskLedger ledger;

    /** 注入自己的代理：从容器里拿到的引用才是被 CGLIB 包装过的那个。@Lazy 拿到的是延迟解析的占位。 */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private TrapService self;

    public TrapService(TaskLedger ledger) {
        this.ledger = ledger;
    }

    /** @Async 方法。被 this 调用就是同步执行，被代理调用才真正进线程池。 */
    @Async
    public void asyncInner(String taskId, String kind) {
        TaskLedger.Entry entry = ledger.begin(taskId, kind);
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ledger.done(taskId, entry);
    }

    /** 等台账里出现指定 kind 的记录（异步执行最早的痕迹是 begin），返回执行线程名。 */
    public String awaitThread(String taskId, String kind, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (TaskLedger.Entry entry : ledger.snapshot(taskId)) {
                if (entry.kind().equals(kind)) {
                    return entry.thread();
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "timeout";
    }

    public Map<String, String> probeSelfInvocation() {
        String taskId = "T" + UUID.randomUUID().toString().substring(0, 8);
        String httpThread = Thread.currentThread().getName();

        // 路径一：this 调用，走不到代理 → 同步执行，线程名与 HTTP 线程相同
        this.asyncInner(taskId, "self-call");
        String selfCallThread = Thread.currentThread().getName();

        // 路径二：从容器注入的代理调用 → 真异步，线程名是 biz-N
        self.asyncInner(taskId, "proxied-call");
        String proxiedThread = awaitThread(taskId, "proxied-call", 5000);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("httpThread", httpThread);
        result.put("selfCallThread", selfCallThread);
        result.put("proxiedCallThread", proxiedThread);
        result.put("selfCallAsync", Boolean.toString(!selfCallThread.equals(httpThread)));
        return result;
    }
}
