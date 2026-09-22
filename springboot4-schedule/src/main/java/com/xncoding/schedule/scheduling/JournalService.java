package com.xncoding.schedule.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行记录（台账）：每次任务执行落一条，供 HTTP 探针与取值单使用。
 *
 * 记录的是运行事实：任务名、触发方式（cron/fixedDelay/fixedRate/manual）、
 * 执行线程、开始时间、耗时、结果。内存环形队列，只留最近 400 条。
 */
@Component
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);
    private static final int CAPACITY = 400;

    public record Entry(long seq, String job, String trigger, String thread,
                        String status, String error, long costMillis, Instant at) {
    }

    private final AtomicLong seq = new AtomicLong();
    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    public void record(String job, String trigger, String thread,
                       String status, String error, long costMillis) {
        Entry entry = new Entry(seq.incrementAndGet(), job, trigger, thread,
                status, error, costMillis, Instant.now());
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.pollFirst();
        }
        log.info("JOB_TICK job={} trigger={} thread={} status={} costMillis={}",
                job, trigger, thread, status, costMillis);
    }

    public List<Entry> recent(String job, int limit) {
        return entries.stream()
                .filter(e -> job == null || job.isBlank() || e.job().equals(job))
                .sorted(Comparator.comparingLong(Entry::seq).reversed())
                .limit(limit)
                .toList();
    }
}
