package com.xncoding.async.ledger;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务台账：每个异步任务（短信 / 积分 / 报表 / 各类探针）一条记录，
 * 按提交时生成的 taskId 分组。HTTP 探针随时吐出快照，S4 验证的
 * 线程名、耗时、异常类型全部从这里取值。
 */
@Component
public class TaskLedger {

    public record Entry(String id, String kind, String status, String thread,
                        long startedAt, long finishedAt, long costMillis, String error) {
    }

    private final Map<String, CopyOnWriteArrayList<Entry>> groups = new ConcurrentHashMap<>();

    /** 任务开始：线程名在这里定格，谁执行谁登记。 */
    public Entry begin(String group, String kind) {
        Entry entry = new Entry(UUID.randomUUID().toString().substring(0, 8), kind, "RUNNING",
                Thread.currentThread().getName(), System.currentTimeMillis(), 0, 0, null);
        groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    public void done(String group, Entry entry) {
        replace(group, entry, new Entry(entry.id(), entry.kind(), "DONE", entry.thread(),
                entry.startedAt(), System.currentTimeMillis(),
                System.currentTimeMillis() - entry.startedAt(), null));
    }

    public void fail(String group, Entry entry, Throwable error) {
        replace(group, entry, new Entry(entry.id(), entry.kind(), "FAILED", entry.thread(),
                entry.startedAt(), System.currentTimeMillis(),
                System.currentTimeMillis() - entry.startedAt(), error.toString()));
    }

    /** 给 AsyncUncaughtExceptionHandler 用：按 taskId + kind 找到 RUNNING 记录改成 FAILED。 */
    public boolean fail(String group, String kind, Throwable error) {
        CopyOnWriteArrayList<Entry> entries = groups.get(group);
        if (entries == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.kind().equals(kind) && "RUNNING".equals(entry.status())) {
                replace(group, entry, new Entry(entry.id(), entry.kind(), "FAILED", entry.thread(),
                        entry.startedAt(), System.currentTimeMillis(),
                        System.currentTimeMillis() - entry.startedAt(), error.toString()));
                return true;
            }
        }
        return false;
    }

    private void replace(String group, Entry oldEntry, Entry newEntry) {
        CopyOnWriteArrayList<Entry> entries = groups.get(group);
        if (entries == null) {
            return;
        }
        int index = entries.indexOf(oldEntry);
        if (index >= 0) {
            entries.set(index, newEntry);
        }
    }

    public List<Entry> snapshot(String group) {
        CopyOnWriteArrayList<Entry> entries = groups.get(group);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** 聚合状态：任一 FAILED 即 FAILED，全部 DONE 即 DONE，否则 RUNNING。 */
    public String aggregate(String group) {
        List<Entry> entries = snapshot(group);
        if (entries.isEmpty()) {
            return "UNKNOWN";
        }
        boolean allDone = true;
        for (Entry entry : entries) {
            if ("FAILED".equals(entry.status())) {
                return "FAILED";
            }
            if (!"DONE".equals(entry.status())) {
                allDone = false;
            }
        }
        return allDone ? "DONE" : "RUNNING";
    }

    public boolean exists(String group) {
        CopyOnWriteArrayList<Entry> entries = groups.get(group);
        return entries != null && !entries.isEmpty();
    }
}
