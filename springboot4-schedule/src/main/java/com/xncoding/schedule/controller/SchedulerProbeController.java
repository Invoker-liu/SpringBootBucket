package com.xncoding.schedule.controller;

import com.xncoding.schedule.exception.BusinessException;
import com.xncoding.schedule.job.ChaosJobs;
import com.xncoding.schedule.job.OrderJobs;
import com.xncoding.schedule.job.ReconJob;
import com.xncoding.schedule.scheduling.DynamicCronTrigger;
import com.xncoding.schedule.scheduling.JournalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度探针：手动触发、动态 cron、演练开关、执行记录查询，全部走 HTTP。
 * S4 验证脚本的每一个取值都从这里或应用日志拿到。
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerProbeController {

    public record CronRequest(@NotBlank String cron) {
    }

    public record ToggleRequest(boolean on) {
    }

    private final ReconJob reconJob;
    private final OrderJobs orderJobs;
    private final ChaosJobs chaosJobs;
    private final DynamicCronTrigger reconCronTrigger;
    private final JournalService journal;

    public SchedulerProbeController(ReconJob reconJob, OrderJobs orderJobs, ChaosJobs chaosJobs,
                                    DynamicCronTrigger reconCronTrigger, JournalService journal) {
        this.reconJob = reconJob;
        this.orderJobs = orderJobs;
        this.chaosJobs = chaosJobs;
        this.reconCronTrigger = reconCronTrigger;
        this.journal = journal;
    }

    /** 手动触发一个任务：与调度共用同一段业务方法，执行线程是 HTTP 线程。 */
    @PostMapping("/trigger/{job}")
    public Map<String, Object> trigger(@PathVariable String job) {
        long start = System.currentTimeMillis();
        String result;
        switch (job) {
            case "recon" -> result = reconJob.runScanOnce("manual") + " files";
            case "cancel" -> result = orderJobs.runCancelOnce("manual") + " cancelled";
            case "report" -> result = orderJobs.runReportOnce("manual").toString();
            case "blacklist" -> result = "costMillis=" + chaosJobs.runBlacklistOnce("manual");
            case "poison" -> {
                // poison 故意抛异常：手动触发同样要接住，探针不能自己 500
                try {
                    chaosJobs.runPoisonOnce("manual");
                    result = "no-op";
                } catch (RuntimeException e) {
                    result = "failed: " + e.getMessage();
                }
            }
            default -> throw new BusinessException(HttpStatus.NOT_FOUND,
                    "未知任务: " + job + "，可选 recon/cancel/report/blacklist/poison");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job);
        body.put("status", result.startsWith("failed") ? "failed" : "ok");
        body.put("result", result);
        body.put("thread", Thread.currentThread().getName());
        body.put("costMillis", System.currentTimeMillis() - start);
        return body;
    }

    @GetMapping("/cron")
    public Map<String, Object> currentCron() {
        return Map.of("job", "recon", "cron", reconCronTrigger.current());
    }

    /** 运行期改对账扫描的 cron，下一次调度按新值算。 */
    @PostMapping("/cron")
    public Map<String, Object> changeCron(@Valid @RequestBody CronRequest request) {
        try {
            String applied = reconCronTrigger.update(request.cron());
            return Map.of("job", "recon", "cron", applied, "effective", "next-scheduling");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "cron 表达式非法: " + request.cron());
        }
    }

    @PostMapping("/blacklist/slow")
    public Map<String, Object> toggleSlow(@Valid @RequestBody ToggleRequest request) {
        chaosJobs.setSlow(request.on());
        return Map.of("slow", chaosJobs.isSlow());
    }

    @PostMapping("/chaos/poison")
    public ResponseEntity<Map<String, Object>> togglePoison(@Valid @RequestBody ToggleRequest request) {
        chaosJobs.setPoison(request.on());
        return ResponseEntity.status(HttpStatus.OK)
                .body(Map.of("poison", chaosJobs.isPoison()));
    }

    @GetMapping("/journal")
    public List<JournalService.Entry> journal(
            @RequestParam(required = false) String job,
            @RequestParam(defaultValue = "30") int limit) {
        return journal.recent(job, Math.min(limit, 200));
    }
}
