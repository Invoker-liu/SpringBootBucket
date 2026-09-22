package com.xncoding.resilience.controller;

import com.xncoding.resilience.service.HotspotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发限制演示接口组。
 *
 * <p>report 走 REJECT 策略：并发压测时满员的请求立刻收到 429，
 * rejected 计数器累加；task 走 BLOCK 策略：满员排队但最终全部成功。
 * stats 汇总接受数、拒绝数与方法体内并发峰值，供 verify 脚本取值。
 */
@RestController
@RequestMapping("/api/hotspot")
public class HotspotController {

    private final HotspotService hotspotService;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    public HotspotController(HotspotService hotspotService) {
        this.hotspotService = hotspotService;
    }

    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestParam(defaultValue = "0") int seq) {
        try {
            String body = hotspotService.rejectReport(seq);
            accepted.incrementAndGet();
            return ResponseEntity.ok(Map.of("seq", seq, "result", body));
        } catch (InvocationRejectedException ex) {
            rejected.incrementAndGet();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("seq", seq, "rejected", true,
                            "reason", ex.getClass().getSimpleName()));
        }
    }

    @PostMapping("/task")
    public Map<String, Object> task(@RequestParam(defaultValue = "0") int seq) {
        String result = hotspotService.blockReport(seq);
        accepted.incrementAndGet();
        return Map.of("seq", seq, "result", result);
    }

    @GetMapping("/stats")
    public Map<String, Integer> stats() {
        return Map.of(
                "accepted", accepted.get(),
                "rejected", rejected.get(),
                "maxInFlight", hotspotService.getMaxInFlight());
    }
}
