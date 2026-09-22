# springboot4-restful

Spring Boot 4 全家桶教程 · 第 1 篇配套工程：**用 Spring MVC 实现一组符合 REST 约定的订单接口**。

本工程不依赖任何外部中间件，数据放在进程内存里，`git clone` 后两条命令即可跑起来。
它的定位是后续 29 个工程的**骨架模板**——分层方式、DTO 约定、错误响应格式、测试写法都从这里复制。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 资源化 URL | 路径只用复数名词 `/api/orders`，动作交给 HTTP 方法 |
| 状态码语义 | 创建 201 + `Location`、查询/更新 200、删除 204、校验失败 400、业务冲突 409/422 |
| 幂等性 | `PUT` 整体替换业务字段，重复提交结果一致；`PATCH` 只改状态子资源 |
| 入参白名单 | 请求体用独立 DTO，客户端无法通过多传字段把订单直接改成"已完成" |
| 参数校验 | Jakarta Bean Validation：请求体 `@Valid`、查询参数约束注解，逐字段错误结构化返回 |
| 统一错误格式 | RFC 9457 `application/problem+json`，由 `@RestControllerAdvice` 统一产出 |
| 分页与排序 | 查询串表达条件，排序字段走白名单，分页顺序稳定不重不漏 |
| 字段脱敏 | 响应体中的手机号按 `138****8000` 输出，避免明文外泄 |
| 测试 | `MockMvcTester` 切片测试 + `RestTestClient` 真实端口端到端测试 |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9、Tomcat 11.0.24、Jackson 3.1.5） |

无需数据库、无需 Docker。

## 三、运行

```bash
cd springboot4-restful

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/springboot4-restful-1.0.0.jar
```

启动日志里看到这一行即成功：

```
Tomcat started on port 8080 (http) with context path '/'
Started RestfulApplication in 1.74 seconds
```

> 如果 `SERVER_PORT` 这类环境变量在本机已存在，它会覆盖 `application.yml` 里的 `server.port`。
> 需要指定端口时显式加参数：`java -jar target/springboot4-restful-1.0.0.jar --server.port=18080`，
> 命令行参数的优先级高于环境变量。

## 四、接口清单

| 方法 | 路径 | 说明 | 成功状态码 |
|---|---|---|---|
| POST | `/api/orders` | 创建订单，订单号由服务端生成 | 201 + `Location` |
| GET | `/api/orders/{id}` | 按主键查询 | 200 |
| GET | `/api/orders` | 分页 + 条件查询 | 200 |
| PUT | `/api/orders/{id}` | 整体替换业务字段（幂等） | 200 |
| PATCH | `/api/orders/{id}/status` | 状态流转 | 200 |
| DELETE | `/api/orders/{id}` | 删除订单 | 204 |

### 查询参数

| 参数 | 默认值 | 约束 |
|---|---|---|
| `keyword` | 无 | 模糊匹配订单号或客户姓名 |
| `status` | 无 | `CREATED` / `PAID` / `SHIPPED` / `COMPLETED` / `CANCELLED` |
| `page` | `0` | ≥ 0 |
| `size` | `10` | 1 ~ 100 |
| `sort` | `createdAt,desc` | 字段取自白名单，方向为 `asc`/`desc` |

### 状态机

```
CREATED ──> PAID ──> SHIPPED ──> COMPLETED
   │          │
   └──────────┴──────> CANCELLED
```

`COMPLETED` 与 `CANCELLED` 是终态：既不接受状态流转，也不允许通过 `PUT` 修改任何业务字段。

## 五、手工测试

### 创建订单

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":299.50,"remark":"演示下单"}'
```

实际响应：

```http
HTTP/1.1 201
Location: http://localhost:8080/api/orders/1
Content-Type: application/json

{"id":1,"orderNo":"ORD2026091718022015","customerName":"熊大","customerPhone":"138****8000",
 "totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "createdAt":"2026-09-17T14:43:09.616177200Z","updatedAt":"2026-09-17T14:43:09.616177200Z"}
```

注意三点：状态码是 201、`Location` 头直接给出新资源地址、手机号已脱敏。

### 分页查询

```bash
curl -s "http://localhost:8080/api/orders?keyword=熊大&size=2&sort=totalAmount,asc"
```

```json
{"list":[ ... ],"page":0,"size":2,"total":1,"totalPages":1}
```

### 状态流转

```bash
# 合法：CREATED -> PAID，返回 200
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H "Content-Type: application/json" -d '{"status":"PAID"}'

# 非法：PAID -> CREATED，返回 422
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H "Content-Type: application/json" -d '{"status":"CREATED"}'
```

非法流转的响应体是一个标准的 problem 文档：

```json
{
  "detail": "订单不允许从 已支付 变更为 已创建",
  "instance": "/api/orders/1/status",
  "status": 422,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

### 字段校验失败

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"","customerPhone":"123","totalAmount":-1}'
```

```json
{
  "detail": "请求体字段校验未通过，逐字段原因见 errors",
  "instance": "/api/orders",
  "status": 400,
  "title": "请求体校验失败",
  "errors": [
    {"field":"customerPhone","message":"客户手机号格式不正确"},
    {"field":"totalAmount","message":"订单金额必须大于 0"},
    {"field":"customerName","message":"客户姓名不能为空"}
  ]
}
```

### 删除

```bash
curl -i -X DELETE http://localhost:8080/api/orders/1   # 204，无响应体
curl -i -X DELETE http://localhost:8080/api/orders/1   # 404 problem+json
```

## 六、运行测试

```bash
mvn test
```

预期结果：

```
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

两个测试类分工不同：

| 测试类 | 技术 | 覆盖内容 |
|---|---|---|
| `OrderApiMockMvcTest` | `@SpringBootTest` + `MockMvcTester` | 15 个用例：状态码、响应头、JSON 结构、状态机、校验、排序回退 |
| `OrderApiEndToEndTest` | `@SpringBootTest(RANDOM_PORT)` + `RestTestClient` | 真实 Tomcat 上跑完整生命周期与 problem 文档结构 |

## 七、工程结构

```
src/main/java/com/xncoding/restful/
├── RestfulApplication.java          启动类
├── controller/
│   └── OrderController.java         资源入口，只做参数绑定与响应组装
├── service/
│   └── OrderService.java            业务规则：状态机、订单号生成、终态保护
├── repository/
│   ├── OrderRepository.java         仓储接口，后续 MyBatis/JPA/MongoDB 篇会替换实现
│   └── InMemoryOrderRepository.java 内存实现，让本工程零外部依赖即可运行
├── domain/
│   ├── Order.java                   不可变领域模型（record）
│   ├── OrderStatus.java             状态枚举 + 状态机
│   ├── OrderPageQuery.java          查询条件，含排序字段白名单与 size 上限
│   └── PageSlice.java               仓储层返回的分页切片
├── dto/
│   ├── OrderCreateRequest.java      创建请求体（带校验注解）
│   ├── OrderUpdateRequest.java      更新请求体
│   ├── OrderStatusRequest.java      状态流转请求体
│   ├── OrderResponse.java           响应体，含手机号脱敏
│   └── PageResponse.java            统一分页响应体
└── exception/
    ├── ResourceNotFoundException.java  404
    ├── BusinessException.java          409 / 422
    └── GlobalExceptionHandler.java     RFC 9457 统一错误输出
```

仓储接口与实现分离是刻意的：后续 MyBatis-Plus、Spring Data JPA、MongoDB 三篇只需换一个
`OrderRepository` 实现类，Controller、Service、DTO、测试全部不用动。

## 八、从 Spring Boot 2.0 迁过来要注意什么

1. **`spring-boot-starter-web` 已弃用**，改用 `spring-boot-starter-webmvc`。
2. **测试 starter 被拆分**。Web 工程用 `spring-boot-starter-webmvc-test`，它已包含
   `spring-boot-starter-test`，并额外带来 `spring-boot-resttestclient`。
3. **`@AutoConfigureMockMvc` 换包了**：`boot.test.autoconfigure.web.servlet` →
   `boot.webmvc.test.autoconfigure`。Boot 4 把测试自动配置按模块拆开，旧导入会直接编译失败。
4. **`@SpringBootTest` 不再隐式启用 MockMvc**，必须显式加 `@AutoConfigureMockMvc`。
5. **不要在控制器类上加 `@Validated`**。Framework 6.1 起方法参数校验已内置，抛
   `HandlerMethodValidationException`；一旦加了 `@Validated`，会退回 AOP 代理校验链路抛
   `ConstraintViolationException`，全局异常处理器里处理 400 的分支就失效了（实测会变成 500）。
6. **Jackson 3 换了包名**：`com.fasterxml.jackson` → `tools.jackson`。Boot 自动配置的
   `JsonMapper` 类型是 `tools.jackson.databind.json.JsonMapper`，可以直接注入。
7. **错误响应的正解是 RFC 9457**，而不是自定义 `{code, msg, data}` 包装类。规范字段
   `type/title/status/detail/instance` 客户端和工具链都认识，不需要额外文档。
8. **`Location` 头是绝对 URI**。反代部署时要配置
   `server.forward-headers-strategy=native`（或 `framework`），否则协议与主机名会取错。

## 九、许可

MIT License，作者 Xiong Neng。
