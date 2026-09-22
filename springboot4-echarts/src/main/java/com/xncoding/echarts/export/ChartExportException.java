package com.xncoding.echarts.export;

/**
 * 导出失败：子进程非零退出、超时、PNG 魔数校验不过都归到这一类，
 * 由控制器统一转成 503 problem+json。
 */
public class ChartExportException extends RuntimeException {

    public ChartExportException(String message) {
        super(message);
    }

    public ChartExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
