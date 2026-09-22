package com.xncoding.schedule.controller;

import com.xncoding.schedule.service.ReconService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对账文件登记接口：给 cron 扫描任务提供数据源。
 */
@RestController
@RequestMapping("/api/recon/files")
public class ReconFileController {

    private final ReconService reconService;

    public ReconFileController(ReconService reconService) {
        this.reconService = reconService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestParam @NotBlank String fileName) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconService.register(fileName));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return reconService.listAll();
    }
}
