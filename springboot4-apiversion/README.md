# springboot4-apiversion

Spring Boot 4 全家桶教程 · 第 22 篇配套工程：**用 Framework 7 的版本化路由做 API 版本管理**。

订单接口 v2 要把客户信息聚成对象、把金额拆成明细，老客户端只认 v1 的平铺快照。本工程让
`/api/orders` 同一路径上 v1 与 v2 两套 controller 并存，请求按 `X-Api-Version` 头或
`api-version` 查询参数路由到对应版本，业务与数据层只有一份。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 版本声明 | 映射注解的 `version` 属性：`@RequestMapping(value="/api/orders", version="1")`，Framework 7 没有 @ApiVersion 注解 |
| 版本解析 | `spring.mvc.apiversion.use.header: X-Api-Version` + `use.query-parameter: api-version`，两条路线并存 |
| 默认版本 | `required: false` + `default: 1`，无版本请求按 v1 处理 |
| 版本白名单 | `supported: 1, 2, 3`，白名单外的合法语义版本 400 |
| 版本范围 | `@GetMapping(version="2+")` 基线写法，2 与 3 都路由到同一方法 |
| 版本注入 | controller 方法参数直接声明 `SemanticApiVersionParser.Version` |
| 版本分叉停在接口层 | 两个版本共用同一个 `OrderService` 与 `OrderRepository`，差异只在 DTO |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9） |

无需数据库、无需 Docker、零额外依赖：API 版本能力全部在 spring-web/spring-webmvc 内。

## 三、运行

```bash
cd springboot4-apiversion

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/springboot4-apiversion-1.0.0.jar
```

应用默认端口 18220（`server.port: ${SERVER_PORT:18220}`）。

> 本机环境注入过 `SERVER_PORT` 变量时它会覆盖 yml；系列惯例是启动前显式
> `export SERVER_PORT=18220`。

## 四、接口清单

| 方法 | 路径 | 版本 | 说明 |
|---|---|---|---|
| GET | `/api/orders` | 1 | 订单列表，完整快照平铺 |
| GET | `/api/orders/{id}` | 1 | 订单详情，完整快照平铺 |
| GET | `/api/orders` | 2 | 订单列表，客户聚合 + 金额明细 |
| GET | `/api/orders/{id}` | 2 | 订单详情，客户聚合 + 金额明细 |
| GET | `/api/version/echo` | 2+ | 版本探针，回显解析出的版本号 |

版本指定方式（任选其一）：

```bash
# 请求头
curl -H "X-Api-Version: 2" http://localhost:18220/api/orders/1

# 查询参数
curl "http://localhost:18220/api/orders/1?api-version=2"

# 不带版本：default=1 生效，落 v1
curl http://localhost:18220/api/orders/1
```

## 五、实测结论（Boot 4.1.1 + Framework 7.0.9，2026-09-20）

全部来自同一次运行，日志见 `.workbuddy/apiversion-verify.log`。

| 请求 | 结果 | 说明 |
|---|---|---|
| 不带版本 | 200，v1 快照结构 | `required: false` + `default: 1` 缺一不可，只配 default 时 400 |
| `X-Api-Version: 1` | 200，v1 快照结构 | 平铺 11 字段 |
| `X-Api-Version: 2` | 200，v2 拆分结构 | customer + items + amount |
| `?api-version=2` | 200，v2 拆分结构 | 与 header 路线等价 |
| `X-Api-Version: abc` | 400 `Invalid API version: 'abc'.` | 解析失败，problem+json |
| `X-Api-Version: 9` | 400 `Invalid API version: '9.0.0'.` | 白名单精确匹配，9 不在 supported |
| `X-Api-Version: 3` 打订单接口 | 400 | 白名单内但订单接口无 3 的映射 |
| `X-Api-Version: 3` 打 echo（2+） | 200 `{"resolved":"3.0.0","major":3,...}` | 范围匹配成功 |
| `X-Api-Version: 2.4.1` 打 echo | 400 | 白名单精确匹配不含小版本 |
| `X-Api-Version: 1` 打 echo（2+） | 404 | 不满足基线，且无其他映射 |

两个关键结论：

1. `required: false` 且无 default 时，无版本请求会同时命中 v1 与 v2 两个映射（实际落 v2）。
   要么配 default，要么 `required: true`，不要裸配 required=false。
2. 白名单是精确集合匹配，`2.4.1` 这类小版本也要列进 supported 才能通过校验。

## 六、运行测试

```bash
mvn test
```

预期结果：

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`ApiVersionEndToEndTest` 用 `@SpringBootTest(RANDOM_PORT)` + `RestTestClient` 起真实
Tomcat，8 个用例逐条覆盖上表的默认版本、双路线路由、非法版本、白名单、范围匹配与并存路由。

## 七、工程结构

```
src/main/java/com/xncoding/apiversion/
├── ApiversionApplication.java       启动类
├── controller/
│   ├── OrderControllerV1.java       v1：/api/orders，version="1"，快照平铺
│   ├── OrderControllerV2.java       v2：同路径，version="2"，客户聚合 + 金额明细
│   └── VersionProbeController.java  版本探针，version="2+"，注入 Version 参数
├── service/
│   └── OrderService.java            业务服务，两个版本共用
├── repository/
│   └── OrderRepository.java         内存仓储，两个版本共用
├── domain/
│   └── Order.java                   领域模型，版本分叉不落在这里
└── dto/
    ├── OrderResponseV1.java         v1 响应体：完整快照
    └── OrderResponseV2.java         v2 响应体：customer/items/amount 子结构
```

## 八、许可

MIT License，作者 Xiong Neng。
