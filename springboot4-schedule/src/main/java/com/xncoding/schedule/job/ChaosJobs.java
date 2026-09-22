package com.xncoding.schedule.job;

import com.xncoding.schedule.scheduling.JournalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 两个演练用任务：慢调用（演示单线程停摆）与故障注入（演示异常处理语义）。
 *
 * blacklistSync 模拟「调远端接口拉黑名单」：slow 开关打开时睡 slow-millis 毫秒，
 * 复现一次慢调用把调度线程占满、其余任务全部停摆的现象。
 * poisonProbe 模拟「远端对账接口 503」：poison 开关打开时直接抛异常，
 * 用于实证「异常不重试、下一次照常触发」。
 */
@Component
public class ChaosJobs {

    private final JournalService journal;
    private final long slowMillis;

    private volatile boolean slow = false;
    private volatile boolean poison = false;

    public ChaosJobs(JournalService journal,
                     @Value("${app.chaos.slow-millis:8000}") long slowMillis) {
        this.journal = journal;
        this.slowMillis = slowMillis;
    }

    /** 黑名单同步：每次结束后 2 秒再来；slow 开着时这次执行耗时显著变长。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    public void blacklistSync() {
        runBlacklistOnce("fixedDelay");
    }

    /** 故障探针：cron 每 6 秒；poison 开着时抛异常。 */
    @Scheduled(cron = "0/6 * * * * *")
    public void poisonProbe() {
        runPoisonOnce("cron");
    }

    public long runBlacklistOnce(String trigger) {
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        try {
            if (slow) {
                Thread.sleep(slowMillis);
            }
            journal.record("blacklist", trigger, thread, "ok",
                    slow ? "slow=" + slowMillis : "fast", System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.currentTimeMillis() - start;
    }

    public void runPoisonOnce(String trigger) {
        if (!poison) {
            return;
        }
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        RuntimeException failure =
                new IllegalStateException("演示异常：远端对账接口 503");
        journal.record("poison", trigger, thread, "failed",
                failure.toString(), System.currentTimeMillis() - start);
        throw failure;
    }

    public void setSlow(boolean slow) {
        this.slow = slow;
    }

    public void setPoison(boolean poison) {
        this.poison = poison;
    }

    public boolean isSlow() {
        return slow;
    }

    public boolean isPoison() {
        return poison;
    }
}
