package com.xncoding.resilience.controller;

import com.xncoding.resilience.domain.ChargeResult;
import com.xncoding.resilience.service.PaymentFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试演示接口组。
 *
 * <p>charge：失败 failTimes 次后成功或走降级，永远 200，返回尝试次数与耗时；
 * charge-raw：不装兜底，重试耗尽后捕获异常原样转成 502，
 * 便于观察原始异常传播路径（在控制器捕获，避免污染应用日志的 ERROR 计数）。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentFacade facade;
    private final AtomicInteger rawAborts = new AtomicInteger();

    public PaymentController(PaymentFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/charge")
    public ChargeResult charge(@RequestParam String orderId,
                               @RequestParam(defaultValue = "2") int failTimes) {
        return facade.chargeWithFallback(orderId, failTimes);
    }

    @PostMapping("/charge-raw")
    public ResponseEntity<?> chargeRaw(@RequestParam String orderId,
                                       @RequestParam(defaultValue = "99") int failTimes) {
        try {
            return ResponseEntity.ok(facade.chargeRaw(orderId, failTimes));
        } catch (com.xncoding.resilience.exception.ChannelUnavailableException ex) {
            rawAborts.incrementAndGet();
            return ResponseEntity.status(502).body(Map.of(
                    "orderId", orderId,
                    "success", false,
                    "fallback", false,
                    "error", ex.getClass().getSimpleName(),
                    "message", String.valueOf(ex.getMessage())));
        }
    }

    @GetMapping("/raw-aborts")
    public Map<String, Integer> rawAborts() {
        return Map.of("rawAborts", rawAborts.get());
    }
}
