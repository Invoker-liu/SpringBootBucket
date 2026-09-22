package com.xncoding.observability.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * OTLP 日志发射器：Spring Boot 不做 SLF4J 日志到 OTel 的自动桥接，
 * 需要业务代码通过 SdkLoggerProvider 的 Logger API 发射记录，
 * 之后才由 BatchLogRecordProcessor 批量推到 management.opentelemetry.logging.export.otlp.endpoint。
 * SdkLoggerProvider 缺席时（如测试关闭 management.opentelemetry.enabled）退回 no-op 实现。
 */
@Component
public class OtlpLogBridge {

    private final Logger otelLogger;

    public OtlpLogBridge(ObjectProvider<SdkLoggerProvider> loggerProvider) {
        SdkLoggerProvider sdk = loggerProvider.getIfAvailable();
        this.otelLogger = sdk != null
                ? sdk.loggerBuilder("com.xncoding.observability.orders").build()
                : LoggerProvider.noop().get("com.xncoding.observability.orders");
    }

    public void emitOrderPlaced(long id, String customer) {
        otelLogger.logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setSeverityText("INFO")
                .setBody("订单已创建（OTLP 日志信号）id=" + id + " customer=" + customer)
                .setAttribute(AttributeKey.longKey("order.id"), id)
                .setAttribute(AttributeKey.stringKey("order.customer"), customer)
                // 关联当前链路，导出的日志带 traceId
                .setContext(Context.current())
                .emit();
    }
}
