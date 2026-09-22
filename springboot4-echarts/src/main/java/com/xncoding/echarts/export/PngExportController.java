package com.xncoding.echarts.export;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 导出接口：POST 一个 JSON 参数体，返回 image/png 字节流（流式直返，不落业务存储）。
 * 参数越界 400；无头浏览器侧任何失败（超时/崩溃/魔数不符）统一 503 problem+json，
 * 告诉调用方「稍后重试可能恢复」，与参数错误的 400 区分开。
 * <p>
 * 错误响应在方法内直接构造 ResponseEntity&lt;ProblemDetail&gt; 并显式指定
 * application/problem+json，不经过 @ExceptionHandler 二次转发——实测 Framework 7.0.9 下
 * 异常处理器转发的 ProblemDetail 会被默认错误页（application/json）接管，媒体类型丢失。
 */
@RestController
@RequestMapping("/api/export")
public class PngExportController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ChartPngExporter exporter;

    public PngExportController(ChartPngExporter exporter) {
        this.exporter = exporter;
    }

    @PostMapping("/png")
    public ResponseEntity<?> exportPng(@RequestBody ExportRequest request) {
        try {
            request.validate();
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, "导出参数不合法", e.getMessage());
        }

        byte[] png;
        try {
            png = exporter.export(request);
        } catch (ChartExportException e) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "导出失败", "图表导出暂不可用：" + e.getMessage());
        }

        String filename = "chart-" + LocalDateTime.now().format(FILE_TS) + ".png";
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(png);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
