package com.xncoding.schedule.job;

import com.xncoding.schedule.scheduling.JournalService;
import com.xncoding.schedule.service.ReconService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账文件扫描任务。
 *
 * 注解上不写 cron：cron 值由 SchedulingSetup 里的 DynamicCronTrigger 提供，
 * HTTP 接口可以在运行期改节奏，下一次调度就按新值算。
 * @Scheduled(fixedDelay/​fixedRate) 的其余任务不受影响，各任务独立计时。
 */
@Component
public class ReconJob {

    private final ReconService reconService;
    private final JournalService journal;

    public ReconJob(ReconService reconService, JournalService journal) {
        this.reconService = reconService;
        this.journal = journal;
    }

    public int runScanOnce(String trigger) {
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        try {
            int processed = reconService.processPending();
            journal.record("recon", trigger, thread, "ok",
                    processed + " files", System.currentTimeMillis() - start);
            return processed;
        } catch (RuntimeException e) {
            journal.record("recon", trigger, thread, "failed",
                    e.toString(), System.currentTimeMillis() - start);
            throw e;
        }
    }
}
