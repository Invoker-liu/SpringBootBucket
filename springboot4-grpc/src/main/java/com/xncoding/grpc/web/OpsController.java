package com.xncoding.grpc.web;

import com.xncoding.grpc.config.FaultControl;
import com.xncoding.grpc.metrics.CallMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 故障注入控制面与计数台账输出 */
@RestController
@RequestMapping("/api")
public class OpsController {

    private final FaultControl fault;
    private final CallMetrics metrics;

    public OpsController(FaultControl fault, CallMetrics metrics) {
        this.fault = fault;
        this.metrics = metrics;
    }

    public record FaultRequest(String mode) {
    }

    @PostMapping("/fault")
    public Map<String, String> setFault(@RequestBody FaultRequest request) {
        fault.set(request.mode());
        return Map.of("mode", fault.current());
    }

    @GetMapping("/metrics")
    public CallMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
