package com.xncoding.schedule.job;

import com.xncoding.schedule.scheduling.JournalService;
import com.xncoding.schedule.service.OrderService;
import com.xncoding.schedule.service.ReportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单业务的两个例行任务。
 *
 * cancelTimeoutOrders 用 fixedDelay：上一次执行「结束」后再等 3 秒，
 * 执行时长波动不会造成重入，适合「清一次就够」的清理型任务。
 * refreshDailyReport 用 fixedRate：按固定节奏对齐，适合「每个整点/每 4 秒」
 * 这种有节拍语义的统计型任务。
 */
@Component
public class OrderJobs {

    private final OrderService orderService;
    private final ReportService reportService;
    private final JournalService journal;

    public OrderJobs(OrderService orderService, ReportService reportService, JournalService journal) {
        this.orderService = orderService;
        this.reportService = reportService;
        this.journal = journal;
    }

    /** 超时未支付订单自动取消：每次执行结束后 3 秒再来一轮。 */
    @Scheduled(fixedDelay = 3000, initialDelay = 4000)
    public void cancelTimeoutOrders() {
        runCancelOnce("fixedDelay");
    }

    /** 每日报表统计：固定节拍，4 秒一轮，首轮延迟 6 秒等数据准备。 */
    @Scheduled(fixedRate = 4000, initialDelay = 6000)
    public void refreshDailyReport() {
        runReportOnce("fixedRate");
    }

    public int runCancelOnce(String trigger) {
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        try {
            int cancelled = orderService.cancelTimeout(15);
            journal.record("cancel", trigger, thread, "ok",
                    cancelled + " cancelled", System.currentTimeMillis() - start);
            return cancelled;
        } catch (RuntimeException e) {
            journal.record("cancel", trigger, thread, "failed",
                    e.toString(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> runReportOnce(String trigger) {
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        try {
            Map<String, Object> result = reportService.buildDailyReport();
            journal.record("report", trigger, thread, "ok",
                    result.toString(), System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            journal.record("report", trigger, thread, "failed",
                    e.toString(), System.currentTimeMillis() - start);
            throw e;
        }
    }
}
