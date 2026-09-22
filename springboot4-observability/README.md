# springboot4-observability

Spring Boot 4 教程第 24 篇配套工程：Actuator + Micrometer + OpenTelemetry 可观测性三件套（指标 / 链路 / 日志），全部在 Boot 4.1.1 上实测。

## 技术栈

- Spring Boot 4.1.1（parent 4.1.1，Java 21）
- spring-boot-starter-actuator（含 micrometer-observation 1.17.1）
- spring-boot-starter-opentelemetry 4.1.1（micrometer-tracing-bridge-otel 1.7.1 + opentelemetry-exporter-otlp 1.62.0 + micrometer-registry-otlp 1.17.1）
- micrometer-registry-prometheus（Prometheus 刮擦端点）
- 端口 18240（本系列统一约定，环境变量 SERVER_PORT 可覆盖）

## 业务场景

订单域最小模型，三类接口对应三种观测场景：

| 接口 | 场景 |
|---|---|
| POST /api/orders | 正常路径，Counter `orders.placed` 计数 + OTLP 日志信号发射 |
| GET /api/orders/{id} | 常规查询 |
| GET /api/orders/{id}/process | Timer `orders.processing` 计时 + Observation 产出子 Span |
| GET /api/orders/{id}/slow | 800ms 慢请求，看 http.server.requests 慢样本 |
| GET /api/orders/{id}/fail | 故意抛 IllegalStateException，验证失败路径计数与异常 Span |

## 构建与运行

```bash
# 构建（跳过测试）
mvn package -DskipTests

# 运行（默认端口 18240）
java -jar target/springboot4-observability-1.0.0.jar
```

## 验证

```bash
# 单元/端到端测试（6 个用例，测试期间自动关闭三个 OTLP 导出器）
mvn test

# 一键端到端：起本机 OTLP 假收集器(4318) + 应用(18240)，curl 各端点取数后自动关停
bash ../.workbuddy/verify-observability.sh
```

验证脚本覆盖：health 明细、自定义指标取值、慢请求与异常的 http.server.requests 样本、prometheus 文本、OTLP 三信号接收计数（/v1/traces、/v1/metrics、/v1/logs）、日志 traceId 抽查。

## 关键配置（application.yml）

- 端点暴露：`management.endpoints.web.exposure.include: health,info,metrics,prometheus`
- 链路导出：`management.opentelemetry.tracing.export.otlp.endpoint: http://127.0.0.1:4318/v1/traces`（Boot 4.1 新前缀）
- 日志导出：`management.opentelemetry.logging.export.otlp.endpoint: http://127.0.0.1:4318/v1/logs`
- 指标导出：`management.otlp.metrics.export.url: http://127.0.0.1:4318/v1/metrics`（Micrometer 前缀未变）
- traceId 进日志：logging pattern 里 `%X{traceId}` / `%X{spanId}`

## 取证结论（Boot 4.1.1 制品实证）

- spring-boot-starter-opentelemetry 存在，聚合 OTLP 三个导出器
- OpenTelemetry 自动配置搬进 spring-boot-opentelemetry 与 spring-boot-micrometer-tracing-opentelemetry 两个新模块
- SLF4J 日志不会自动桥接到 OTel，需通过 SdkLoggerProvider 的 Logger API 发射（见 OtlpLogBridge）
- 计数器名以 created 结尾会被 Prometheus 命名约定吞掉（orders.created 导出成 orders_total）

作者：Xiong Neng
