package com.xncoding.echarts.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PNG 导出器：ProcessBuilder 拉起 venv python 的 playwright 导出脚本，
 * 无头浏览器打开图表页，渲染完成后对图表容器截屏，写临时文件再读回字节流。
 * <p>
 * 三个工程化点：单次尝试有总超时（进程级 waitFor，超时强杀）；失败重试一次；
 * 读回的字节先验 PNG 魔数，浏览器半路崩掉产出的残file不会混进响应。
 */
@Service
public class ChartPngExporter {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final String pythonExecutable;
    private final String scriptPath;
    private final int timeoutSeconds;
    private final int maxAttempts;

    public ChartPngExporter(
            @Value("${echarts.export.python:python}") String pythonExecutable,
            @Value("${echarts.export.script-path:scripts/export_png.py}") String scriptPath,
            @Value("${echarts.export.timeout-seconds:45}") int timeoutSeconds,
            @Value("${echarts.export.max-attempts:2}") int maxAttempts) {
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * @return PNG 字节流（已通过魔数校验）
     */
    public byte[] export(ExportRequest request) {
        String url = request.chartUrl(ExportRequest.currentBaseUrl());
        Path output;
        try {
            output = Files.createTempFile("echarts-export-", ".png");
        } catch (IOException e) {
            throw new ChartExportException("创建导出临时文件失败", e);
        }
        try {
            IOException lastIo = null;
            String lastStderr = "";
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    runOnce(url, request, output);
                    return readVerifiedPng(output);
                } catch (IOException e) {
                    lastIo = e;
                    lastStderr = String.valueOf(e.getMessage());
                }
            }
            throw new ChartExportException("导出重试 " + maxAttempts + " 次仍失败：" + lastStderr, lastIo);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    private void runOnce(String url, ExportRequest request, Path output) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(scriptPath);
        command.add("--url");
        command.add(url);
        command.add("--output");
        command.add(output.toAbsolutePath().toString());
        command.add("--width");
        command.add(String.valueOf(request.width()));
        command.add("--height");
        command.add(String.valueOf(request.height()));
        command.add("--timeout");
        command.add(String.valueOf(timeoutSeconds));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output_text;
        try {
            boolean finished = process.waitFor(timeoutSeconds + 10L, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("导出子进程超过 " + (timeoutSeconds + 10) + " 秒被强杀");
            }
            output_text = new String(process.getInputStream().readAllBytes());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("导出等待被中断", e);
        }

        if (process.exitValue() != 0) {
            throw new IOException("导出脚本退出码 " + process.exitValue() + "，输出尾部："
                    + tail(output_text, 400));
        }
    }

    private byte[] readVerifiedPng(Path output) throws IOException {
        byte[] bytes = Files.readAllBytes(output);
        if (bytes.length < 8) {
            throw new IOException("导出产物为空或过小（" + bytes.length + " 字节）");
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                throw new IOException("导出产物不是合法 PNG（魔数不匹配）");
            }
        }
        return bytes;
    }

    private String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
    }
}
