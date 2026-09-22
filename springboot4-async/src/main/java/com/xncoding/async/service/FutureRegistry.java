package com.xncoding.async.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 积分任务的 future 登记处：状态查询接口靠它把 future 里的异常捞出来。 */
@Component
public class FutureRegistry {

    private final Map<String, CompletableFuture<Integer>> futures = new ConcurrentHashMap<>();

    public void put(String taskId, CompletableFuture<Integer> future) {
        futures.put(taskId, future);
    }

    public CompletableFuture<Integer> get(String taskId) {
        return futures.get(taskId);
    }
}
