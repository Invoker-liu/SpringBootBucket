# springboot4-restclient：使用声明式 HTTP 客户端 RestClient

Spring Boot 4.1.1 + JDK 21。订单服务通过 `@HttpExchange` 声明式接口调用物流服务与风控服务：接口即契约，URL 与参数收进类型安全的方法签名，`RestClient.Builder` 注入后按上游各自装配 baseUrl 与超时，`HttpServiceProxyFactory` 生成接口代理。

## 运行

```bash
# 上游 stub（物流/风控/控制面，18141）+ 本应用（18140）
bash .workbuddy/verify-restclient.sh
```

单独启动应用（需自备上游，或把 base-url 指向任一兼容服务）：

```bash
bash .workbuddy/tools/mvn.sh -f springboot4-restclient/pom.xml spring-boot:run
```

端口：`${SERVER_PORT:18140}`。上游地址与超时在 `application.yml` 的 `upstream.*`。

## 测试

```bash
bash .workbuddy/tools/mvn.sh -f springboot4-restclient/pom.xml test
```

7 个测试全绿：

| 测试类 | 数量 | 内容 |
|---|---|---|
| `DeclarativeClientTest` | 4 | `@RestClientTest` 切片 + `MockRestServiceServer`：POST 打桩断言请求体与 `X-Source` 头、GET 断言 `{no}` 路径变量映射、风控 500 抛 `RestClientResponseException` |
| `OrderServiceDegradeTest` | 3 | 风控超时（`ResourceAccessException`）降级仍建运单、风控 500 降级带状态码、运单失败不阻断下单 |

注意：`spring-boot-starter-webmvc-test` 不包含 `spring-boot-restclient-test`，`@RestClientTest` 需要后者；Boot 4.1.1 中注解在 `org.springframework.boot.restclient.test.autoconfigure`。

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | 下单。内部先调风控 `/risk/check`（超时/非 2xx 降级），再调物流 `/logistics/waybills` 建运单 |
| GET | `/api/orders/{orderNo}` | 订单详情，内部走 `@GetExchange` 查运单最新状态 |
| GET | `/api/orders/metrics` | 调用计数台账：风控/物流调用数、降级命中数、平均与最近耗时 |

声明式接口（`com.xncoding.restclient.api`）：

- `LogisticsApi`：`@HttpExchange("/logistics")`，`@PostExchange("/waybills")` 建运单、`@GetExchange("/waybills/{no}")` 查运单
- `RiskApi`：`@HttpExchange("/risk")`，`@PostExchange("/check")` 下单校验

## 实测结论（verify-restclient.sh 单次运行）

- 正常下单 138ms：风控往返 74ms + 建运单 4ms，服务端链路合计 78ms
- 风控超时降级 1522ms：stub 拖 3s，1500ms read-timeout 触发 `ResourceAccessException`，订单照常落库
- 风控 5xx 降级 12ms：`RestClientResponseException` 捕获后带状态码降级
- 恢复后下单 23ms；metrics 对账 riskCalls=4 / riskPass=2 / timeout=1 / error=1 / waybill=4 / waybillErrors=0
- 上游实录请求头：`X-Source: order-service`、`Content-Type: application/json`、`User-Agent: Java-http-client/21.0.10`（JDK HttpClient）
- 应用日志 ERROR 0 行，RISK_DEGRADE 2 行、ORDER_PLACED 4 行与场景数一致

## 关键事实（S1 制品取证）

- `spring-boot-starter-restclient` = starter + starter-jackson + `spring-boot-restclient`（后者再带 `spring-boot-http-client` 与 http-converter）
- 自动配置包：`org.springframework.boot.restclient.autoconfigure`（含 `.service.HttpServiceClientAutoConfiguration`）
- `spring.http.client.*` 自 4.0.0 废弃，正名 `spring.http.clients.*`；另有 `spring.http.serviceclient.<group>.*` 服务分组配置
- `@HttpExchange` 家族：`org.springframework.web.service.annotation`；`HttpServiceProxyFactory`：`org.springframework.web.service.invoker`；`RestClientAdapter`：`org.springframework.web.client.support`
- Framework 7 的 `HttpServiceProxyFactory.Builder` 用 `exchangeAdapter(...)`（6.x 的 `adapter(...)` 已不在）
- `RestClientCustomizer` 在 `org.springframework.boot.restclient`

完整推导见 `.workbuddy/research/springboot4-restclient.md`。
