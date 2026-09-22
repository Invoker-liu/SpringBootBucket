---
title: SpringBoot4系列24 - 使用Actuator与OpenTelemetry可观测性
slug: sb4-observability
date: 2026-09-20 19:30:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Actuator, Micrometer, OpenTelemetry ]
draft: false
---

上个月支付网关出了一次故障，客服那边的投诉先进来，我这边才反应过去翻日志。日志有，但散在四个服务实例里，想回答「昨晚十点支付接口慢在哪」，我得先找到慢的判断依据，再去四个实例里捞请求日志，最后把两次跨服务调用的日志按请求对上号。这三件事分别对应指标、链路、日志，工程上叫可观测性三件套。复盘的时候我意识到，排障有个更早的阶段：问题还没发生时，系统就该把三类信号持续吐出去，指标给告警提供阈值，链路给定位提供调用路径，日志给复盘提供细节。

Spring Boot 对这件事的答案是 Actuator 暴露端点、Micrometer 统一指标门面、OpenTelemetry 负责把链路和日志信号按 OTLP 协议导出。Boot 4.1 在这层动了不少手术：模块拆分、属性前缀改名、starter 重组，我从网上找来的 3.x 配置片段有一半直接抄过来不生效。所以这篇我把三件套在 Spring Boot 4.1.1 上完整接了一遍：starter 坐标取证、端点暴露、自定义指标、OTLP 三信号导出到本地假收集器、traceId 贯穿日志，端到端验证脚本的每个数字都在正文里对得上。

## 端点与暴露

动手接线的第一件事，是把三个 starter 的坐标钉下来。我翻了本地 Maven 仓库的制品和 spring-boot-dependencies 4.1.1 的 POM，抄录如下：

```text
spring-boot-starter-actuator 4.1.1
  ├─ spring-boot-starter-micrometer-metrics
  ├─ spring-boot-actuator-autoconfigure
  ├─ spring-boot-health
  ├─ micrometer-observation 1.17.1
  └─ micrometer-jakarta9 1.17.1

spring-boot-starter-opentelemetry 4.1.1
  ├─ spring-boot-micrometer-tracing-opentelemetry
  ├─ spring-boot-opentelemetry
  ├─ micrometer-registry-otlp 1.17.1
  ├─ micrometer-tracing-bridge-otel 1.7.1
  └─ opentelemetry-exporter-otlp 1.62.0
```

actuator 的 starter 坐标没改名。挺有意思的是 OpenTelemetry 这次有了专属 starter，Boot 3 时代要手动凑四个依赖，4.1 一个坐标全带上。版本号一个都不用写，micrometer 1.17.1、micrometer-tracing 1.7.1、opentelemetry 1.62.0 三条版本线都在 Boot BOM 里管理。

模块拆分是 Boot 4 的大动作。OpenTelemetry 相关自动配置从 spring-boot-actuator-autoconfigure 里整体搬了出去，我在 actuator-autoconfigure 的 AutoConfiguration.imports 里搜了一遍，已经找不到任何 OTel 类。新家有两处：spring-boot-opentelemetry 模块（包名 org.springframework.boot.opentelemetry.autoconfigure）管 OpenTelemetrySdk 和日志导出；spring-boot-micrometer-tracing-opentelemetry 模块（包名 org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure）管链路导出，里面有 OpenTelemetryTracingAutoConfiguration 和 OtlpTracingAutoConfiguration 两个入口类。你要是还按 3.x 的包名去翻源码，直接换这两个包名找。

工程的依赖声明只有四个：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

webmvc 提供宿主接口，actuator 带出端点体系，opentelemetry starter 接通链路与日志的 OTLP 导出，prometheus registry 补上 Pull 模式的刮擦端点，四个依赖正好覆盖三件套的两个出口方向：Push 走 OTLP，Pull 走 prometheus。这里我顺带提醒一句，Boot 没有 prometheus 专属 starter，最后这一行要手动加。

starter 加完还不算完，默认只暴露 health 一个端点，这个默认值来自 spring-boot-actuator-autoconfigure 的配置元数据：management.endpoints.web.exposure.include 默认值就是 ['health']。演示工程我打开了四个：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

health 的 show-details 打开后，磁盘、线程池、liveness 这些组件明细会出现在响应里。我启动应用访问 /actuator/health，返回 status UP，components 里 diskSpace 带着总容量 484949618688 字节、剩余 441346002944 字节。生产环境按需收敛，health 明细可能泄露内部结构，配合 management.endpoints.web.exposure.exclude 或独立管理端口（management.server.port）做隔离都是常规做法。

prometheus 端点是文本刮擦格式，注册它的是 spring-boot-micrometer-metrics 模块里的 PrometheusMetricsExportAutoConfiguration。我刮了一次，59 个 TYPE 行，JVM 内存、Tomcat 线程池、磁盘、执行器指标全部在列。metrics 端点则是 JSON 格式的指标目录，先访问 /actuator/metrics 列出全部指标名，再访问 /actuator/metrics/<指标名> 读具体取值，这个两级结构我后面读自定义指标时会用到。

端点先暴露再谈安全。prometheus 文本和 metrics 目录都会完整展示内部指标结构，health 明细里的路径、容量属于环境信息。这三类端点放进生产之前，至少要做一层隔离：管理流量与业务流量分开，端口的来源限制到运维网段。工程里有 Spring Security 的话，端点权限用角色收口到运维角色；容器部署时把管理端口映射到内部网络，不经过业务负载均衡。演示工程为了验证方便我全部打开了，安全边界你自己按环境收紧。基础端点就位，下一步把业务信号接进来。

## 自定义指标

示例业务我选了订单域，五个接口：下单、查询、处理、慢请求、故意失败。慢请求固定睡 800 毫秒，失败接口抛 IllegalStateException，这两个是专门留出来观测慢样本和错误路径的。OrderService 里我注册了两个自定义指标：

```java
this.createdCounter = Counter.builder("orders.placed")
        .description("累计创建的订单数")
        .tag("channel", "web")
        .register(registry);
this.processingTimer = Timer.builder("orders.processing")
        .description("订单处理耗时")
        .tag("outcome", "ok")
        .publishPercentiles(0.5, 0.95)
        .register(registry);
```

指标命名用点分小写，Micrometer 会在各导出口做命名映射：prometheus 端点里点号换成下划线，Counter 自动补 _total 后缀，Timer 自动带 _seconds 单位。所以 orders.placed 在 prometheus 里叫 orders_placed_total，orders.processing 叫 orders_processing_seconds。两套名字容易搞混：在 actuator 的 metrics 端点里读数用原始名，在 prometheus 文本里 grep 用映射后的名，两套名字对应同一个指标。

Counter 在下单时 increment 一次，Timer 用 record 包住处理逻辑：

```java
public Order process(long id) {
    get(id);
    return processingTimer.record(() ->
            Observation.createNotStarted("orders.process", observations)
                    .lowCardinalityKeyValue("order.stage", "process")
                    .observe(() -> {
                        sleep(300);
                        return markPaid(id);
                    }));
}
```

计时之外，处理逻辑还套了一层 Observation，这是我有意为之的。Observation 是 Micrometer 的统一观测 API，一次调用同时产出两个东西：指标里的耗时记录，链路里的一个子 Span。这是 Micrometer Tracing 路线的核心设计：业务代码面向 Observation 编程，指标和 trace 自动分流到各自的导出器，换存储后端不用改业务代码。lowCardinalityKeyValue 只放 stage 这类低基数值，你要是把订单号放进去，标签基数会把存储撑爆，这是自定义指标最常见的事故源。

验证脚本建了两笔订单、处理了一次，然后从 actuator 读回取值：

```text
GET /actuator/metrics/orders.placed
→ {"name":"orders.placed","measurements":[{"statistic":"COUNT","value":2.0}],
   "availableTags":[{"tag":"channel","values":["web"]}]}

GET /actuator/metrics/orders.processing
→ {"name":"orders.processing","baseUnit":"seconds",
   "measurements":[{"statistic":"COUNT","value":1.0},{"statistic":"TOTAL_TIME","value":0.3028589}]}
```

orders.placed 计数 2.0，对上两笔订单。orders.processing 记了 1 个样本，总耗时 0.3028589 秒，对上 process 接口里 300 毫秒的 sleep 加业务开销。JSON 里的 statistic 有 COUNT、TOTAL_TIME、MAX 三种，分位数要等 prometheus 端点里看。

慢接口的 800 毫秒落在框架自带的 http.server.requests 指标里。每个 HTTP 请求框架默认都记一行，method、uri、outcome、status 四个标签起步，异常类名自动进 exception 标签。我按路由模板和结果过滤后读回：

```text
GET /actuator/metrics/http.server.requests?tag=uri:/api/orders/{id}/slow&tag=outcome:SUCCESS
→ COUNT 1.0, TOTAL_TIME 0.8111099, tags: method=GET, status=200

GET /actuator/metrics/http.server.requests?tag=uri:/api/orders/{id}/fail&tag=outcome:SERVER_ERROR
→ COUNT 1.0, TOTAL_TIME 0.0059243, tags: exception=IllegalStateException, status=500
```

慢请求 811 毫秒、失败请求 5.9 毫秒加 500 状态。失败路径不用业务代码埋点，framework 层的观测已经把错误类别记全了。指标端点的查询参数里那个 {id} 是路由模板，不是展开后的订单号，我第一次就拿着展开后的订单号去查，白忙一场，这个坑后面细说。

注入 MeterRegistry 前后还有一层全局加工的机会。management.observations.key-values.region=cn-east 这类配置给全部观测挂公共标签，机房、版本号这类每实例一个值的属性用它带出去；代码侧的等价物是 MeterRegistryCustomizer，在指标注册前统一加 common tags 或设置计量规则。公共标签和业务标签的边界要想清楚：region 放标签，订单号放 trace 属性，前者每实例一个值，后者每请求一个值，混着用会把存储和查询都拖垮。

编程接口之外还有注解路线。@Observed、@Timed、@Counted 三个注解打在方法上，配合 management.observations.annotations.enabled=true 开启注解扫描，方法耗时与调用次数自动进指标。这里藏着一个静默失效的坑：注解扫描靠 AOP 实现，spring-boot-starter-aspectj（或 aspectjweaver）得先进依赖，只开属性不加依赖时注解不报错也不生效。我的用法是注解适合快速给存量方法补观测，编程式 Observation 适合需要动态键值的场景，两种写法产出同一种指标，后端无差别对待。

## OTLP 链路导出

指标、链路、日志三个信号在 Boot 4.1 里有三套属性前缀，这件事我是从三个 jar 的 spring-configuration-metadata.json 里各抄了一份才敢下结论的：

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    resource-attributes:
      service.name: springboot4-observability
      service.namespace: springboot4-bucket
    tracing:
      export:
        otlp:
          endpoint: http://127.0.0.1:4318/v1/traces
        schedule-delay: 2s
    logging:
      export:
        otlp:
          endpoint: http://127.0.0.1:4318/v1/logs
  otlp:
    metrics:
      export:
        url: http://127.0.0.1:4318/v1/metrics
        step: 5s
```

链路前缀是 management.opentelemetry.tracing.export.otlp.endpoint，Boot 3 的 management.otlp.tracing.endpoint 降级成兼容别名。日志前缀 management.opentelemetry.logging.export.otlp.endpoint，旧别名同理。指标前缀没动，还是 management.otlp.metrics.export.url，因为指标导出走 Micrometer 的 OtlpMeterRegistry，和 OTel SDK 的 SdkMeterProvider 完全无关。官方文档明确说过：Boot 不创建 SdkMeterProvider bean，OTel API 产生的指标不会被导出。还有个我差点栽进去的细节：三行 endpoint 都要写到 /v1/traces、/v1/metrics、/v1/logs 完整路径，OTel 导出器对信号专属 endpoint 不自动补路径，只写 host 会一个包都发不出去。

resource-attributes 两个键值得单独说。service.name 是后端里区分服务的唯一线索，OTLP 包里没有它，所有数据都会混在一起。这个属性同时喂两个体系：OTel 的 Resource bean 和 Micrometer OtlpMeterRegistry 的 resource attributes，我启动后翻日志，确认信息直接就在里面：

```text
INFO i.m.c.i.p.PushMeterRegistry - Publishing metrics for OtlpMeterRegistry
  every 5s to http://127.0.0.1:4318/v1/metrics
  with resource attributes {service.name=springboot4-observability, service.namespace=springboot4-bucket}
```

环境变量也能进：OTEL_SERVICE_NAME 和 OTEL_RESOURCE_ATTRIBUTES 会被 OpenTelemetryEnvironmentVariableEnvironmentPostProcessor 读入并与属性合并，配置属性优先。management.opentelemetry.enabled 设 false 相当于 OTEL_SDK_DISABLED，全部信号退回 no-op 实现，context 传播保留。采样比例 management.tracing.sampling.probability 我设了 1.0，这是演示用法，生产按 0.1 左右权衡导出量，配合 schedule-delay 控制批量节奏。

![](https://static.xiongneng.me/observability-otlp-export-components-20260922062313.png)

三条导出链路的组件关系如上图：业务代码只面对 MeterRegistry、ObservationRegistry、SdkLoggerProvider 三个门面，出口侧四个导出器各管一段，属性前缀的三套体系对应图中三段箭头。

导出目标不是现成的收集器，我本机没有 OpenTelemetry Collector，所以验证脚本先用 Python 起了一个假收集器，代码不到 50 行，收 POST 计数不解析 protobuf：

```python
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        rec = state.setdefault(self.path, {"count": 0, "bytes": 0})
        rec["count"] += 1
        rec["bytes"] += length
        # 各路径计数实时写文件，供验证脚本读取
```

它只做一件事：按请求路径记条数和字节数，回 200。端到端验证跑完，假收集器的接收记录是这样：

```text
/v1/traces  count=3  bytes=11105
/v1/logs    count=2  bytes=802
/v1/metrics count=3  bytes=36732
```

三个信号都有真实流量落地。traces 3 次导出对应建单、处理、慢请求、失败这几条请求链路的批量上报，schedule-delay 压到 2 秒，每个请求的 HTTP Span 加业务 Observation 产出的 orders.process 子 Span 都在里面；metrics 3 次对应 5 秒一个导出周期；logs 2 次是两笔订单的 OTLP 日志信号。字节数都在涨，导出路径实打实通了。

以 process 接口为例看一次请求在链路里的形状。GET /api/orders/1/process 进来，框架先开一个 HTTP 服务端 Span，名字是路由模板，带 method、uri、status、outcome 属性；业务代码里的 orders.process Observation 挂在这个父 Span 下面，300 毫秒的处理时长就是它和父 Span 的时间差。收集器侧拿到的每个批次里，Span 之间靠 traceId 和 parentSpanId 两个字段构成树，接进 Jaeger 或 Grafana Tempo 这类后端，就能看到一次请求从入口到业务处理的完整轨道。慢请求在轨道上一眼可见：父 Span 拉出 811 毫秒，子 Span 缺席说明慢在框架层与控制器之间，子 Span 长说明慢在业务内部，这就是链路信号对定位的实际贡献。

![](https://static.xiongneng.me/observability-request-observation-sequence-20260922062313.png)

把时序展开成上图：一次下单请求同步经过的每个环节都有信号产出，三路导出按各自的批量窗口异步落地，底部一行是本轮验证的真实计数。

## 日志关联 traceId

日志信号在 Boot 4.1 里有个官方声明的边界，我原样抄在这里：SdkLoggerProvider bean 会自动创建，OTLP 日志导出器也接好了，但应用日志不会自动桥接进去。SLF4J 的每一行日志默认不会变成 OTel LogRecord 发出去。要让 /v1/logs 有流量，两条路：引入 OTel 的 logback appender 桥接（io.opentelemetry.instrumentation 系，版本线归 instrumentation BOM 管，不在 Boot BOM 里），或者业务代码直接用 OTel Logger API 发射。演示工程我选了后者，零额外依赖：

```java
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
                .setBody("订单已创建（OTLP 日志信号）id=" + id + " customer=" + customer)
                .setAttribute(AttributeKey.longKey("order.id"), id)
                .setContext(Context.current())
                .emit();
    }
}
```

构造器用 ObjectProvider 注入 SdkLoggerProvider，测试场景关掉 OTel 自动配置时 bean 缺席，退回 no-op 实现不炸启动。emit 按批聚合，management.opentelemetry.logging.export.schedule-delay 默认 1 秒，发射后最多等 1 秒就能在收集器侧看到计数。setContext(Context.current()) 把当前链路上下文挂上去，导出的 LogRecord 自带 traceId，日志和 trace 在后端能直接对上。

控制台日志的 traceId 是另一件事，靠 Micrometer Tracing 写 MDC 完成。链路开启后，请求线程里的业务日志会自动带上 MDC 键值，logging pattern 里显式带出：

```yaml
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [traceId=%X{traceId:-} spanId=%X{spanId:-}] %logger{24} - %msg%n"
```

验证脚本数了一遍，应用日志 19 行带 traceId，抽三条看格式：

```text
19:23:59.471 INFO  [traceId=26f8baf5d5385c1e106bf53998b30e57 spanId=dbc06921d42b4afb] c.x.o.c.OrderController - 订单已创建 id=1 customer=张三
19:24:00.114 INFO  [traceId=7abb96c830ab6e9a09396c873d7c10ca spanId=ef0e0e88ea6070e7] c.x.o.c.OrderController - 开始处理订单 id=1
19:24:02.065 ERROR [traceId= spanId=] o.a.c.c.C.[.[.[.[dispatcherServlet] - Servlet.service() ... IllegalStateException: 模拟支付网关不可用
```

请求线程里的业务日志全部有 traceId 和 spanId，两条日志同一个 traceId 就能判定同一条请求链路，这就是跨实例对账的铁证，排障时先按 traceId 聚合再按时间排序，调用次序一目了然。最后一条 ERROR 是失败接口抛的，traceId 为空，我查了下原因：异常已经抛出请求线程，Tomcat 的错误处理线程没有链路上下文。这行 ERROR 是全脚本唯一的 ERROR，来自故意失败接口，属于验证目标本身。

控制台之外还有第三种出口：logging.structured.json 系列属性能把日志直接格式化成 JSON，traceId 作为字段输出，接入日志平台时比正则解析文本模式省事。三种出口（文本模式、结构化 JSON、OTLP 信号）共享同一个 MDC 与链路上下文，按需选用。

线程池与异步代码是上下文断裂的高发区。@Async 方法进线程池后 traceId 会丢，spring.task.execution.propagate-context=true 让 Boot 自动装配的 AsyncTaskExecutor 把上下文带过去；Reactor 管线对应 spring.reactor.context-propagation=auto。这两处不开，链路一到异步边界就断成两截，日志的 traceId 同步丢失。你要排查异步超时问题，先查这两行配置。

## 完整案例

整条接线的全貌先看架构图：

![](https://static.xiongneng.me/observability-architecture-20260922062313.png)

把上面的部件串成一次端到端验证。脚本共 11 步：起假收集器、起应用、health 检查、建两笔订单、读自定义指标、读慢请求与失败指标、等导出落地、数日志 traceId、关服务。我给脚本配了五类断言：端点可访问性、指标取值与业务动作对账、prometheus 文本与 metrics 端点同源、OTLP 三信号接收计数、日志 traceId 覆盖率。应用启动耗时 2.889 秒，全部取值来自同一次运行，数字之间互相印证，任何一处对不上都说明接线有问题。

mvn test 有 6 个端到端用例，真实启动 Tomcat 随机端口逐项验证：health 明细可读；创建两笔订单后 orders.placed 计数不低于 2；process 接口计时后 orders.processing 有样本；fail 接口返回 500 且 prometheus 文本里出现 outcome="SERVER_ERROR" 与 uri="/api/orders/{id}/fail"；prometheus 端点含 orders_placed_total 与 http_server_requests_seconds_count；不存在的订单返回 404。测试上下文里用三个属性关掉 OTLP 导出（management.opentelemetry.enabled=false 加两个导出开关），避免没有收集器时的告警噪音。

prometheus 端点共 59 个 TYPE 行，自定义指标的输出长这样：

```text
orders_placed_total{channel="web"} 2.0
orders_processing_seconds{outcome="ok",quantile="0.5"} 0.301989888
orders_processing_seconds{outcome="ok",quantile="0.95"} 0.301989888
orders_processing_seconds_count{outcome="ok"} 1
orders_processing_seconds_sum{outcome="ok"} 0.3028589
orders_processing_seconds_max{outcome="ok"} 0.3028589
```

完整取值面板如下，四个分区自上而下对应指标读数、prometheus 文本、OTLP 计数与日志抽查：

![](https://static.xiongneng.me/observability-verify-panel.png)

orders_placed_total 2.0 与 metrics 端点读数一致，一个来源两个出口，Pull 和 Push 各走各的。orders_processing 的 p50 和 p95 都是 0.301989888 秒，样本只有一个，两个分位数收敛到同一个值，这是正常现象；慢请求进来自动就能看到 p95 抬升，样本攒到几十个后分位数才呈现梯度。

验证产物我都留了底：验证脚本一条命令跑完整链路，跑完把产物（prometheus 文本、OTLP 接收计数、应用日志）原样存档，换环境复跑一遍就能对比取值。

这套接线的最后一步是换掉假收集器。OpenTelemetry Collector 官方发行版一个容器就能起，OTLP receiver 默认监听 4318，把三行 endpoint 里的 127.0.0.1 换成收集器地址，应用侧一行代码不改。收集器把 metrics 转给 Prometheus、traces 转给 Tempo、logs 转给 Loki，三件套在后端各就各位；假收集器验证过的接线部分（属性前缀、路径、批量节奏、traceId 注入）全部原样生效，替换的只是包的落点。告警规则建在指标上：http_server_requests_seconds_count 按 outcome 分组做增速告警，orders_placed_total 突降说明下单入口出问题，这些规则的取值口径与本文验证脚本读到的数字完全一致。

导出数字本身也挺有意思，我单独拎出来复盘。logs 计数 2 恰好等于建单次数 2，说明两笔订单的 OTLP 日志记录在 1 秒批量窗口里各自成批发出；traces 计数 3 小于请求数 6，是因为 schedule-delay 的批量把多个请求的 Span 合进同一批，收集器侧看到的是批次不是单个请求。你排查「导出量对不上请求数」时先看批量参数，再看采样比例，两个旋钮都在 management.opentelemetry.tracing.export 下面。

## 避坑指南

下面每一条都是我这次实测亲手踩到或差点踩到的，不是文档摘抄。

**坑一，属性前缀三套并存，抄错一个信号全哑。** 链路是 management.opentelemetry.tracing.export.otlp.*，日志是 management.opentelemetry.logging.export.otlp.*，指标是 management.otlp.metrics.export.*。照 3.x 老配置抄 management.otlp.tracing.endpoint，链路导出静默不生效，旧前缀只是兼容别名，文档里已不推荐。第一次接 OTLP 导出扑空，九成是这个原因。判别办法我吃了一次亏才记住：解开目标 jar 看 spring-configuration-metadata.json，别信记忆。

**坑二，OTLP endpoint 要写完整信号路径。** 只配 http://127.0.0.1:4318，traces 一个包都发不出。通用环境变量 OTEL_EXPORTER_OTLP_ENDPOINT 会被自动追加 /v1/traces，但信号专属属性按字面使用。三行配置都带 /v1/xxx 后缀最稳。

**坑三，计数器名以 created 结尾会被吞。** 我把 Counter 起名 orders.created，prometheus 端点导出的名字却是 orders_total，created 这一节被 Prometheus 命名约定当成 OpenMetrics 的时间戳后缀处理掉了。后来改名 orders.placed，导出名 orders_placed_total 才正常。

**坑四，应用日志不会自动桥接 OTLP。** SdkLoggerProvider 和导出器自动就位，/v1/logs 却一个包都没有。SLF4J 到 OTel 的桥接要引 instrumentation 系的 logback appender，或者像本文用 Logger API 手动发射。以为 starter 一加日志就上云的，等排障时才会发现日志平台是空的。

**坑五，日志导出开关的默认值要查清。** management.logging.export.otlp.enabled 默认 true，配上 endpoint 就生效。测试环境没有收集器时会持续告警，我给测试用了三个属性整体关掉 OTel 导出，context 才安静。

**坑六，http.server.requests 的 uri 标签是路由模板。** 过滤失败请求时查 uri:/api/orders/1/fail 永远查不到，真实标签值是 uri:/api/orders/{id}/fail。metrics 端点的查询参数里传大括号还有二次坑：RestTestClient 把它当 URI 变量展开报错，curl 发裸大括号被 Tomcat 400 拒掉，得用 %7B%7D 编码。我从 prometheus 文本断言，最省事。

**坑七，OTel 自动配置类的包名换了地方。** Boot 3 在 org.springframework.boot.actuate.autoconfigure.opentelemetry，4.1 搬到 org.springframework.boot.opentelemetry.autoconfigure 与 org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure。IDE 里按老包名排除自动配置会扑空。

**坑八，注入 MeterRegistry 时留意注册的是哪个 registry。** 主运行时同时存在 Simple、Prometheus、OTLP 多个 registry，业务代码拿到的 MeterRegistry 是组合视图，写进去各出口都能看到。单独断言某个 registry 的内容时，从对应端点读，别直接看业务代码里的对象。

**坑九，Observation 的高基数标签撑爆指标。** order.id 这类每请求一个值的键，放进 lowCardinalityKeyValue 会让指标时间线爆炸。Observation 的键值分高基数低基数两档，订单号这类只能进 trace 的 attribute，指标标签只放 stage、outcome 这类有限枚举。

**坑十，OTLP 导出失败不阻断业务。** 收集器挂掉时应用照常服务，导出器按批重试后打 WARN（io.opentelemetry.exporter 调到 WARN 级别降噪）。这是可观测性组件的正确姿势，但也意味着信号丢了不会有人报错，收集器的存活要靠平台侧监控。

**坑十一，分位数样本太少没有意义。** publishPercentiles(0.5, 0.95) 在样本只有 1 个时两个分位数输出同一个值。看 p95 要么攒样本，要么改用 percentileHistogram 交给后端聚合。

**坑十二，prometheus 指标需要手动补依赖。** Boot 没有 prometheus starter，micrometer-registry-prometheus 不在依赖里时，exposure.include 写了 prometheus 也不会有这个端点，Actuator 的端点注册以对应 registry 的存在为前提。

**坑十三，测试期记得整体关掉 OTel 导出。** @SpringBootTest 默认继承主配置，三行 endpoint 照样生效，测试期间每个用例都在往不存在的收集器发包。management.opentelemetry.enabled 一个开关退回 no-op，比逐个关导出器干净。

## 小结

三件套怎么接，我的建议按信号分头走。指标这条线最省心，Micrometer 门面稳定，Counter 和 Timer 声明在业务代码，actuator 端点读数、prometheus 刮擦、OTLP 推送三个出口共享同一份数据，接入成本接近零；链路走 Observation，一个 API 同时产出指标和 Span，业务代码不用关心后端是谁，采样比例和批量节奏按导出量权衡；日志最特殊，traceId 进控制台日志靠 MDC，进 OTLP 靠显式发射，SLF4J 桥接是官方声明不做的部分，接入前先把这条边界弄清楚。从 3.x 迁 4.x 的项目，把属性前缀的三套体系、starter 的模块重组、以 created 结尾的指标名陷阱过一遍，雷区基本就排完了。

没解决的事也直说：假收集器只证明包发到了本机 4318 端口，后端侧我还没接真 Collector，告警规则和可视化面板都停在纸面上。等我把 Collector 加 Prometheus、Tempo、Loki 的组合在自己环境里跑通，用同一套验证脚本对着真后端再测一轮，到时候回来补后端侧的接线实录。

## 参考链接

- [Spring Boot 4.1 Actuator 文档](https://docs.spring.io/spring-boot/4.1/reference/actuator.html)：端点暴露与 management 属性总览
- [Spring Boot 4.1 Observability 文档](https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html)：OTel 三信号属性与环境变量映射表
- [Micrometer Observation 概念](https://docs.micrometer.io/micrometer/reference/observation.html)：Observation API 与指标/链路分流设计
- [OpenTelemetry OTLP 规范](https://opentelemetry.io/docs/specs/otlp/)：/v1/traces、/v1/metrics、/v1/logs 协议定义
- [spring-boot-starter-opentelemetry 4.1.1（Maven Central）](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-opentelemetry/4.1.1/)：本文坐标与依赖清单的制品实证来源
