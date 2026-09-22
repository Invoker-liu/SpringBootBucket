---
title: SpringBoot4系列14 - 使用声明式HTTP客户端RestClient
slug: sb4-restclient
date: 2026-10-20 20:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, RestClient, HTTP客户端 ]
draft: false
---

订单服务刚上线那阵子，我对它发出去的两个上游调用心里一直没底：风控服务在下单前校验这笔单子有没有问题，物流服务在支付后生成运单。第一版实现里，这两个调用散落在 Service 的各个角落：URL 字符串硬编码在方法体里，参数拼接靠手写格式化，响应解析各写一套，超时一个都没配。后来风控服务某次发布期 503 了半小时，订单服务整段跟着不可用，我排查时发现三处调用点了三套错误处理，其中一处直接把异常吞了，单子就这么无声无息地放过去了。

![](https://static.xiongneng.me/restclient-manual-vs-declarative-20260922025025.png)

说实话，问题不在「会不会发 HTTP」，在契约没有形状。URL、方法、参数、响应类型这些信息分散在代码各处，改一个上游接口得全工程搜字符串。声明式客户端把方向反过来：定义一个接口，方法签名就是 HTTP 契约，框架在运行时生成代理，调用接口方法等于发请求。这篇我拿 Spring Boot 4.1.1 的 RestClient 把这套方案完整跑了一遍：订单服务通过两个 `@HttpExchange` 接口分别对接物流与风控，超时逐客户端配置，非 2xx 与超时走不同的降级路径，全部结论都带实测数字。

## 编程模型与自动配置的事实

写代码之前我习惯先把依赖组成和类位置确认清楚，免得写 import 全靠猜。第一件事实来自 starter 制品解包（4.1.1 实测）：

```text
spring-boot-starter-restclient
  ├─ spring-boot-starter
  ├─ spring-boot-starter-jackson
  └─ spring-boot-restclient
       ├─ spring-boot
       ├─ spring-boot-http-client
       │    └─ spring-web    7.0.9
       └─ spring-boot-http-converter
```

`spring-boot-restclient` 是 Boot 4 拆模块后的专属模块，HTTP 客户端相关的能力分成两层：`spring-boot-http-client` 放连接工厂与超时这套底层设施，`spring-boot-restclient` 放 RestClient 的自动配置。声明式接口所需的 `spring-web` 随 `spring-boot-http-client` 传递进来，不需要单独引入。

自动配置一共五条（`AutoConfiguration.imports` 实测）：

```text
org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
org.springframework.boot.restclient.autoconfigure.RestClientObservationAutoConfiguration
org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration
org.springframework.boot.restclient.autoconfigure.RestTemplateObservationAutoConfiguration
org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration
```

五条里对本篇起作用的就两条。`RestClientAutoConfiguration` 从字节码看只干一件事：把容器里的 `RestClientBuilderConfigurer` 应用到一个新 Builder 上，产出可注入的 `RestClient.Builder` bean。Configurer 在装配时合入三样东西：全局 `ClientHttpRequestFactoryBuilder`、`spring.http.clients.*` 属性解析出的 `HttpClientSettings`、容器里全部 `RestClientCustomizer`。`HttpServiceClientAutoConfiguration` 服务的是配置化分组路线，把 `spring.http.serviceclient.<group>.*` 绑定成每组独立的连接参数。

属性前缀的正式形态有两组。全局连接参数在 `spring.http.clients.*` 下：

```text
spring.http.clients.connect-timeout      默认连接超时
spring.http.clients.read-timeout         默认读取超时
spring.http.clients.redirects            重定向处理策略
spring.http.clients.ssl.bundle           SSL bundle 名称
spring.http.clients.imperative.factory   连接工厂选型 httpcomponents|jetty|reactor|jdk|simple
spring.http.clients.reactive.connector   响应式连接器选型
spring.http.clients.cookie-handling      Cookie 处理策略
```

分组配置在 `spring.http.serviceclient.*` 下，每组是一个键值映射，可用 `base-url`、`default-header`、`connect-timeout`、`read-timeout` 等字段，配合 Framework 的 `@ImportHttpServices(group=…)` 注解能把整组接口的注册收进配置文件。我最后走的是代码装配路线：`RestClient.Builder` 注入后逐客户端加工，理由是两个上游的超时要各自配置，代码里的控制粒度更直接。

核心类的包位置清单（spring-web 7.0.9 与 Boot 4.1.1 制品解包实证），写 import 时照它核对：

```text
org.springframework.web.service.annotation   @HttpExchange / @GetExchange / @PostExchange
                                             @PutExchange / @DeleteExchange / @PatchExchange
org.springframework.web.service.invoker      HttpServiceProxyFactory / HttpServiceMethod
                                             PathVariableArgumentResolver / RequestBodyArgumentResolver
org.springframework.web.client.support       RestClientAdapter
org.springframework.web.service.registry     @ImportHttpServices / HttpServiceGroupConfigurer
org.springframework.boot.restclient          RestClientCustomizer
org.springframework.boot.restclient.autoconfigure  RestClientAutoConfiguration / RestClientBuilderConfigurer
org.springframework.boot.http.client         ClientHttpRequestFactoryBuilder / HttpClientSettings
```

装配代理时 `HttpServiceProxyFactory.Builder` 的挂适配器方法名是 `exchangeAdapter`，传 `RestClientAdapter.create(restClient)`。这个方法名与注解、适配器的包位置都属于当前事实清单，照抄即可，别凭印象写。

![](https://static.xiongneng.me/restclient-starter-map-20260922025043.png)

## 依赖和配置

工程依赖两块：`starter-webmvc` 支撑订单服务自身的接口，`starter-restclient` 提供声明式客户端：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restclient</artifactId>
</dependency>
```

测试 starter 两个都要，原因在测试一节展开：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restclient-test</artifactId>
    <scope>test</scope>
</dependency>
```

配置文件里没有 RestClient 专属配置段，上游地址与超时以自定义前缀声明，逐客户端各一份：

```yaml
server:
  port: ${SERVER_PORT:18140}

upstream:
  logistics:
    base-url: ${LOGISTICS_BASE_URL:http://localhost:18141}
    connect-timeout: 2s
    read-timeout: 3s
  risk:
    base-url: ${RISK_BASE_URL:http://localhost:18141}
    connect-timeout: 2s
    read-timeout: 1500ms
```

我给风控的读超时压到 1500 ms，因为它是下单链路上的同步卡点，宁可降级也不能让用户干等；物流建运单允许慢一点，3 s 内返回都算正常。这组差异用 properties record 收口：

```java
@ConfigurationProperties(prefix = "upstream")
public record UpstreamProperties(Client logistics, Client risk) {

    public record Client(String baseUrl,
                         @DefaultValue("2s") Duration connectTimeout,
                         @DefaultValue("3s") Duration readTimeout) {
    }
}
```

每个上游一组 `baseUrl + connectTimeout + readTimeout`，配合 `@ConfigurationPropertiesScan` 在启动时绑定。换环境只改配置文件，代码里不出现环境地址。

## 声明式接口：契约收进方法签名

两个上游各定义一个接口，类级 `@HttpExchange` 定基础路径，方法级注解定子路径与动词：

```java
@HttpExchange("/logistics")
public interface LogisticsApi {

    @PostExchange("/waybills")
    Waybill createWaybill(@RequestBody WaybillRequest request);

    @GetExchange("/waybills/{no}")
    Waybill getWaybill(@PathVariable String no);
}
```

```java
@HttpExchange("/risk")
public interface RiskApi {

    @PostExchange("/check")
    RiskResult check(@RequestBody RiskRequest request);
}
```

`{no}` 是路径变量占位符，`@PathVariable` 把方法参数填进占位符；`@RequestBody` 把参数对象交给消息转换器序列化成 JSON。参数注解必须写： Framework 的参数解析器只认注解，一个裸参数会让代理在调用时抛 `IllegalStateException: No suitable resolver`，这条我在测试阶段实测复现过。请求与响应都是普通 record，JSON 编解码由 starter 带入的 Jackson 3 处理，包名 `tools.jackson`。

到这里 HTTP 契约已经有形状了：接口文件就是上游 API 的完整目录，改字段看编译错误，查调用看方法引用。

## 装配：Builder 注入、逐客户端超时、代理生成

装配集中在 HttpClientConfig，一个上游两个 bean：先从注入的 `RestClient.Builder` 加工出独立 RestClient，再用 HttpServiceProxyFactory 生成接口代理：

```java
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient logisticsRestClient(RestClient.Builder builder, UpstreamProperties props) {
        UpstreamProperties.Client cfg = props.logistics();
        return builder
                .requestFactory(factory(cfg.connectTimeout(), cfg.readTimeout()))
                .baseUrl(cfg.baseUrl())
                .defaultHeader("X-Source", "order-service")
                .build();
    }

    @Bean
    RestClient riskRestClient(RestClient.Builder builder, UpstreamProperties props) {
        UpstreamProperties.Client cfg = props.risk();
        return builder
                .requestFactory(factory(cfg.connectTimeout(), cfg.readTimeout()))
                .baseUrl(cfg.baseUrl())
                .defaultHeader("X-Source", "order-service")
                .build();
    }

    @Bean
    LogisticsApi logisticsApi(RestClient logisticsRestClient) {
        return proxyOf(logisticsRestClient, LogisticsApi.class);
    }

    @Bean
    RiskApi riskApi(RestClient riskRestClient) {
        return proxyOf(riskRestClient, RiskApi.class);
    }

    private static ClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return ClientHttpRequestFactoryBuilder.jdk().build(settings);
    }

    private static <T> T proxyOf(RestClient restClient, Class<T> apiType) {
        return HttpServiceProxyFactory.builder()
                .exchangeAdapter(RestClientAdapter.create(restClient))
                .build()
                .createClient(apiType);
    }
}
```

三个装配点各有分工。`requestFactory` 决定连接层：`ClientHttpRequestFactoryBuilder.jdk()` 选 JDK 内置 HttpClient，`HttpClientSettings.defaults().withConnectTimeout(...).withReadTimeout(...)` 把两层超时写进工厂。JDK HttpClient 默认不带请求超时，不配 readTimeout 的调用会无限等，这个默认值我第一次看到时愣了一下，避坑指南里还会再提它。`defaultHeader` 给每个请求带上 `X-Source: order-service`，上游日志按它识别调用方。`proxyOf` 是声明式的核心三行：`RestClientAdapter` 把 RestClient 包成 `HttpExchangeAdapter`，`HttpServiceProxyFactory` 为接口生成 JDK 动态代理，之后容器里注入的 `RiskApi`、`LogisticsApi` 调用起来与本地 bean 无差别。

![](https://static.xiongneng.me/restclient-order-flow-20260922025039.png)

代理生成的开销发生在启动期，运行期的调用路径是「方法调用 → 组装请求 → RestClient 发出 → 反序列化成 record」。我实测了一轮正常下单：风控往返 74 ms，建运单 4 ms，服务端链路合计 78 ms，整段 HTTP 响应 138 ms。声明式代理自身开销在个位数毫秒以内，与手写调用处在同一量级，这一层可以放心。

## 错误处理与降级：两条失败路径分开接

下游服务的失败分两类：超时与连接失败是资源层问题，非 2xx 是业务层问题。RestClient 对非 2xx 的默认行为是抛 `RestClientResponseException`，状态码挂在异常对象上；超时与连接失败抛 `ResourceAccessException`，JDK HttpClient 的 `HttpTimeoutException` 包在 cause 里。按外层异常类型分流，降级策略就能分开写：

```java
private RiskDecision checkRisk(String orderNo, BigDecimal amount) {
    metrics.incRiskCalls();
    long t0 = System.nanoTime();
    try {
        RiskResult result = riskApi.check(new RiskRequest(orderNo, amount));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        metrics.recordRiskLatency(ms);
        metrics.incRiskPass();
        return new RiskDecision("PASS", result.reason(), ms);
    } catch (ResourceAccessException e) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        metrics.recordRiskLatency(ms);
        metrics.incRiskTimeoutDegrades();
        log.warn("RISK_DEGRADE orderNo={} type=timeout cause={} elapsedMs={}",
                orderNo, e.getCause() == null ? "n/a" : e.getCause().getClass().getSimpleName(), ms);
        return new RiskDecision("DEGRADE_TIMEOUT", "risk service timeout", ms);
    } catch (RestClientResponseException e) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        metrics.recordRiskLatency(ms);
        metrics.incRiskErrorDegrades();
        log.warn("RISK_DEGRADE orderNo={} type=status status={} elapsedMs={}",
                orderNo, e.getStatusCode().value(), ms);
        return new RiskDecision("DEGRADE_ERROR", "risk service returned " + e.getStatusCode().value(), ms);
    }
}
```

风控在这里只是校验，不是拦截：超时降级标记 `DEGRADE_TIMEOUT`，5xx 降级标记 `DEGRADE_ERROR`，两种情况下订单都继续走完物流建运单，标记写进订单记录供人工复核。降级路径同样要记调用计数，`CallMetrics` 里的 LongAdder 语义计数器在出问题时能对出「上游挂了多久、降级了几单」，对不上账八成是哪条路径漏记了。物流建运单的失败处理同构：捕获后运单置空、计数器加一，下单链路不断。

## 完整案例：一次下单的完整往返

端到端验证我用一个 Python 起的上游 stub（18141 端口，提供 `/logistics/*` 与 `/risk/*` 两个路由外加一个故障注入控制面），应用跑在 18140，全部请求真发 HTTP。验证脚本一轮跑完七个场景，数字与截图取自同一次运行，我没有手工拼任何一个数。

场景一是正常下单。`POST /api/orders` 请求体 `{"orderNo":"SK-9001","amount":"299.00","itemCount":3}`，内部先走风控再走物流：

```json
{"orderNo": "SK-9001", "amount": 299.0,
 "risk": {"decision": "PASS", "reason": "ok", "riskLatencyMs": 74},
 "waybill": {"waybillNo": "WB00001", "carrier": "STO", "status": "CREATED"},
 "riskLatencyMs": 74, "waybillLatencyMs": 4, "totalMs": 78}
```

![](https://static.xiongneng.me/restclient-orders-panels-20260920022919.png)

场景二查订单详情，`GET /api/orders/SK-9001` 内部走 `@GetExchange` 查运单状态，返回 `waybillStatus: IN_TRANSIT`，往返 34 ms。场景三做故障注入：控制面把风控路由切成延迟模式，响应拖到 3 s，超过 1500 ms 读超时后客户端抛异常进降级分支，整单耗时 1522 ms，其中 1513 ms 在等超时触发。场景四切 500 模式，风控返回 5xx，降级分支 12 ms 走完。场景五恢复后复下一单，23 ms 全绿。每一单的响应结构与场景一同构，decision 字段区分三种结论。

场景六做计数对账，`GET /api/orders/metrics` 输出全部计数器：

```json
{"ordersPlaced": 4, "riskCalls": 4, "riskPass": 2,
 "riskTimeoutDegrades": 1, "riskErrorDegrades": 1,
 "waybillCalls": 4, "waybillErrors": 0,
 "avgRiskLatencyMs": 398, "lastRiskLatencyMs": 3, "lastWaybillLatencyMs": 2}
```

对账恒等式一眼能看：4 次风控调用 = 2 次通过 + 1 次超时降级 + 1 次 5xx 降级，4 次建运单 0 失败。平均风控延迟 398 ms 第一眼看着吓人，其实是被 1513 ms 的超时那一单拉高的，恢复后的单次延迟 3 ms 才是上游的健康水位，看均值得先想想里面混了什么。

![](https://static.xiongneng.me/restclient-metrics-panels-20260920022919.png)

场景七看上游视角。stub 记录下应用发出的请求头实录：

```text
Content-Type: application/json
X-Source: order-service
User-Agent: Java-http-client/21.0.10
Connection: Upgrade, HTTP2-Settings
Upgrade: h2c
Accept-Encoding: deflate
Transfer-encoding: chunked
```

`X-Source` 是配置里加的调用方标识，`User-Agent` 则是连接工厂走 JDK HttpClient 的铁证。有两个挺有意思的细节：JDK 客户端默认尝试 HTTP/2 明文升级（`Upgrade: h2c`），上游不支持时自动回落 HTTP/1.1，无需配置；POST 请求体走 `Transfer-encoding: chunked`，上游解析请求体时按 chunked 读才能拿到完整报文。应用日志侧全程 ERROR 0 行，`RISK_DEGRADE` 2 行、`ORDER_PLACED` 4 行，与场景数一致。

## 测试怎么写

测试我分了两层：接口映射与错误语义用 `@RestClientTest` 切片打桩验证，降级编排用纯 Mockito 单测验证。

`@RestClientTest` 启动一个只装配 HTTP 客户端相关 bean 的切片上下文，注入的 `RestClient.Builder` 已经挂上 `MockRestServiceServer`，桩的期望断言请求行、头、体，响应按需伪造：

```java
@RestClientTest
@Import(DeclarativeClientTest.TestClients.class)
class DeclarativeClientTest {

    @Autowired
    LogisticsApi logisticsApi;

    @Autowired
    RiskApi riskApi;

    @Autowired
    MockRestServiceServer server;

    @Test
    void waybillPost_carriesBodyAndHeader() {
        server.expect(requestTo("http://localhost:18141/logistics/waybills"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source", "order-service"))
                .andExpect(jsonPath("$.orderNo").value("SK-2001"))
                .andExpect(jsonPath("$.itemCount").value(3))
                .andRespond(withSuccess(
                        """
                        {"waybillNo":"WB90001","carrier":"STO","status":"CREATED"}
                        """, MediaType.APPLICATION_JSON));

        Waybill waybill = logisticsApi.createWaybill(new WaybillRequest("SK-2001", 3));

        assertThat(waybill.waybillNo()).isEqualTo("WB90001");
        server.verify();
    }
}
```

期望链上四件事：URL 指向上游绝对地址、方法与头带调用方标识、请求体两个 JSON 字段符合契约、响应回放一段真实结构的 JSON。`server.verify()` 强制所有期望都被消费，请求没发出去或发错字段测试直接红。错误语义的用例把响应换成 `withServerError()`，断言调用侧抛出 `RestClientResponseException` 且状态码 500，对应生产代码里的降级入口。

![](https://static.xiongneng.me/restclient-test-slice-20260922025134.png)

TestClients 是测试专用的装配：从注入的 Builder build 出一个 RestClient，再派生两个接口代理。为什么不直接搬生产配置类？我试过，切片当场就红：生产配置类给每个 RestClient 覆盖了 `requestFactory`，会把 mock 服务端从请求路径上顶掉；mock 定制器也只允许绑定一个 RestClient 实例，从同一个 Builder build 两次就触发 `bound to more than one RestClient`。切片里只验证代理与桩，两层超时的行为放到端到端验证里实测。

降级编排用 Mockito 直接 mock 两个接口，不依赖切片：

```java
@Test
void riskTimeout_degradesAndStillCreatesWaybill() {
    when(riskApi.check(any()))
            .thenThrow(new ResourceAccessException("Read timed out",
                    new HttpTimeoutException("no response within 1500 ms")));
    when(logisticsApi.createWaybill(any()))
            .thenReturn(new Waybill("WB90010", "STO", "CREATED"));

    OrderService.PlacedOrder order = service().placeOrder("SK-3001", new BigDecimal("88.00"), 2);

    assertThat(order.risk().decision()).isEqualTo("DEGRADE_TIMEOUT");
    assertThat(order.waybill().waybillNo()).isEqualTo("WB90010");
}
```

mock 抛 `ResourceAccessException` 并塞一个 `HttpTimeoutException` 进 cause，复现的是超时异常的真实包装结构；断言两件事：decision 标记正确，运单照常生成。三个降级用例（超时、5xx、运单失败）加上切片的四个映射用例，7 个测试全部通过。

测试依赖有一个坑位要交待：`spring-boot-starter-webmvc-test` 不包含 `spring-boot-restclient-test`，`@RestClientTest` 注解与 `MockRestServiceServer` 自动配置都在后者里，两个 starter 要一起引入。注解的包名是 `org.springframework.boot.restclient.test.autoconfigure`。

## 避坑指南

**坑一，接口参数必须显式标注 `@RequestBody` 与 `@PathVariable`。** Framework 的参数解析器只认注解参数，裸参数在调用时抛 `IllegalStateException: No suitable resolver`，编译期毫无征兆。我这条是在测试阶段撞上的，每个方法参数都标注，别赌默认行为。

**坑二，`@RestClientTest` 需要专属 test starter。** `spring-boot-starter-webmvc-test` 的依赖清单里没有 restclient-test，缺它切片起不来或注解找不到。测试侧引入 `spring-boot-starter-restclient-test`，注解包名 `org.springframework.boot.restclient.test.autoconfigure`。

**坑三，测试里别从同一个 Builder build 多个 RestClient。** mock 定制器只能绑定一个实例，build 第二个就报 `bound to more than one RestClient`。多个接口代理共用一个 RestClient 实例即可，接口的类级 `@HttpExchange` 路径本来就是隔离的。

**坑四，生产配置里的 `requestFactory` 会把 mock 顶掉。** `RestClient.Builder` 上后设置的 `requestFactory` 覆盖先设置的 mock 工厂，切片里的桩从此收不到请求。测试用独立配置类装代理，超时行为交给端到端验证。

**坑五，JDK HttpClient 默认没有请求超时。** 不配 readTimeout 的调用在上游假死时会无限等待，线程逐个被占满。`HttpClientSettings.defaults().withConnectTimeout(...).withReadTimeout(...)` 两层都写，同步卡点的上游把读超时压到用户可接受的量级。

**坑六，超时异常在 cause 链里。** 外层抛的是 `ResourceAccessException`，`HttpTimeoutException` 在 cause 位置。分流逻辑按外层类型 catch，要看根因再下探 cause，直接按内层类型 catch 会漏。

**坑七，降级路径也要计数。** 降级不是把异常吞掉，是换一条语义明确的路径，decision 标记、计数器、耗时三项都要落。计数器按「总调用 = 通过 + 各类降级」的恒等式对账，对不上说明有路径漏记。

**坑八，POST 请求体默认走 chunked。** JDK 客户端不带 Content-Length 发流式请求体，上游若按定长读取会拿到空 body。自研上游按 chunked 解析，第三方上游不支持时改用字节数组请求体显式定长。

## 小结

如果上游调用还在散着写，先收 `@HttpExchange` 接口这一步，收益最大且几乎零成本：契约进了方法签名，改上游接口从全工程搜字符串变成看编译错误。超时按上游角色分开配，同步卡点压紧、旁路放宽；失败分流按外层异常类型接，`ResourceAccessException` 管资源层、`RestClientResponseException` 管业务层，降级留标记、留计数。接口多、分组清晰的工程可以看配置化分组路线（`@ImportHttpServices` + `spring.http.serviceclient.*`），需要逐客户端精调就用本篇的代码装配路线，两条路不冲突。

没解决的事也说清楚：故障注入目前只有延迟与 5xx 两种模式，连接拒绝、慢速响应这类更贴近真实网络的故障还没进场景；stub 的控制面要手切，没有和测试串成自动流程。等这批补齐，熔断与重试的正经接入才有干净的实验底座。

## 参考链接

- [Spring Framework REST Endpoints（HTTP Service Clients）](https://docs.spring.io/spring-framework/reference/integration/rest.html#rest-http-service-client)：`@HttpExchange` 声明式客户端与 `HttpServiceProxyFactory` 的官方说明
- [Spring Framework RestClient](https://docs.spring.io/spring-framework/reference/web/rest-client.html)：RestClient 编程模型、请求工厂与异常语义
- [Spring Boot Reference - REST Clients](https://docs.spring.io/spring-boot/reference/web/restclient.html)：`RestClient.Builder` 自动配置、`spring.http.clients.*` 与 `spring.http.serviceclient.*` 属性
- [@HttpExchange Javadoc](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/web/service/annotation/HttpExchange.html)：注解族与参数注解的 API 参考
- [Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/index.html)：`@RestClientTest` 切片测试与 MockRestServiceServer 用法
- [spring-web 7.0.9](https://repo1.maven.org/maven2/org/springframework/spring-web/7.0.9/)：本文包位置与方法签名的制品实证来源
