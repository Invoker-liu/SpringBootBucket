---
title: SpringBoot4系列01 - 实现RESTful接口
slug: sb4-restful
date: 2026-09-17 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, RESTful ]
draft: false
---

前两天在群里看到有人贴了一段接口返回，长这样。

```json
{"code": 0, "msg": "success", "data": {"id": 1, "name": "张三"}}
```

出错了也是这个结构，只是 `code` 变成 1，`msg` 变成错误信息，HTTP 状态码永远是 200。贴出来的人说前端已经按这个格式对接了，改不动了。

说实话我盯着这段 JSON 看了好一会儿。HTTP 本来就把「成功了」和「失败了」分得清清楚楚，状态码就是干这个的，用 `code: 0` 再造一遍，等于把一套全世界都认识的通用语言，换成一套只有公司里五个人认识的方言。监控系统看不懂，网关、APM、抓包工具也看不懂，Nginx 日志里一水儿的 200，出了事想按状态码告警都没处下手。

RFC 9457 那套标准错误格式，SpringBoot 4 把它做成了框架的一等公民，一行配置就能打开。

这篇讲两件事：怎么把一组 REST 风格的接口写扎实，以及 SpringBoot 4 在这个主题上我踩过的关键约定。代码全部可跑，工程在 `springboot4-restful` 目录，不需要数据库和 Docker，`git clone` 下来两条命令就能起来。文末那些响应都是我实际跑出来逐字抄的，一个字没改。

## 先说清楚 REST 到底在约束什么

很多号称「RESTful 接口」的服务，拆开看只是「返回 JSON 的接口」。约束就那么几条，但每一条都有人违反，我评审接口文档时圈出来最多的也是这几处。

![](https://static.xiongneng.me/rest-resource-methods-20260921204934.png)

最容易被违反的一条：URL 里只放名词，动词交给 HTTP 方法。`/api/orders` 是资源，`POST` 是动作。不要写 `/api/orders/create`、`/api/getOrderList`、`/api/orders/deleteById`。这条看着简单，落在代码里就是「方法名即路径」的惯性，写的时候手比脑子快。

然后是状态码，这一条是重灾区。创建成功是 201，不是 200。删除成功是 204，不是 200。资源不存在是 404，不是自定义的 `code: 40401`。参数校验没过是 400，业务规则不允许是 409 或者 422。我一开始也觉得这只是体面问题，后来想明白了：这些状态码是写给网关、负载均衡、监控告警、SDK、重试逻辑看的，人只是顺便看看。

再说幂等。`GET`、`PUT`、`DELETE` 是幂等的，调一次和调十次，服务端的状态一样。`POST` 不幂等，调两次就下两单。这条约束直接影响接口设计：「修改订单金额」应该用 `PUT`，「订单状态流转」更适合 `PATCH`，「提交订单」只能是 `POST`。

还有一条最容易被忘掉：接口契约要稳定，别把内部结构泄漏出去。这条我放到工程骨架那一节细说，那里正好有两个现成的例子。

六个接口的最终形态如下。

| 方法 | 路径 | 动作 | 成功状态码 |
|---|---|---|---|
| POST | `/api/orders` | 创建订单 | 201 + Location |
| GET | `/api/orders/{id}` | 按主键查询 | 200 |
| GET | `/api/orders` | 分页 + 条件查询 | 200 |
| PUT | `/api/orders/{id}` | 整体替换业务字段 | 200 |
| PATCH | `/api/orders/{id}/status` | 状态流转 | 200 |
| DELETE | `/api/orders/{id}` | 删除订单 | 204 |

路径里没有一个动词，全是名词。状态流转那条用的是子资源的形式，`status` 本身就是订单的一个维度。

## SpringBoot 4 的关键约定

坑基本都集中在这一块，我按「最容易踩」到「容易忘」的顺序写。

![](https://static.xiongneng.me/modularization-20260921204934.png)

先说最容易忽略的一个：Web 场景的 starter 是 `spring-boot-starter-webmvc`。

| 场景 | 当前 starter |
|---|---|
| Web MVC | `spring-boot-starter-webmvc` |
| AOP | `spring-boot-starter-aspectj` |
| OAuth2 资源服务器 | `spring-boot-starter-security-oauth2-resource-server` |
| Web Services | `spring-boot-starter-webservices` |

写成 `spring-boot-starter-web` 能编译能运行，但该坐标已标记 deprecated，未来某个版本会移除，等于在依赖树里留一颗不报错的雷。我第一次新建工程时就顺手写错了，编译通过、接口能通，还觉得自己挺顺，两天后翻依赖树才看见那行 deprecated 标记。工程里现在还躺着这个坐标的，九成是这个原因。

测试 starter 按特性拆分，每个主 starter 都有对应的测试 starter，Web 工程用 `spring-boot-starter-webmvc-test`。

这个包带进 Spring Boot 的测试设施，还包括 `spring-boot-resttestclient`，即 4.x 新增的统一 REST 测试客户端。测试写法后面贴实际代码。

官方另提供 `spring-boot-starter-classic` 和 `spring-boot-starter-test-classic` 两个聚合包，定位是让既有工程按原有依赖结构先跑起来。新工程直接用拆分后的 starter，没必要背着一个聚合包过日子。

Jackson 3 的包名前缀是 `tools.jackson`。例外是 `jackson-annotations` 模块，groupId 和包名都还在 `com.fasterxml.jackson` 下。

拉出来的依赖树是这样的。

```
tools.jackson.core:jackson-databind:3.1.5
+- com.fasterxml.jackson.core:jackson-annotations:2.21
\- tools.jackson.core:jackson-core:3.1.5
```

整个依赖树里没有 `jackson-datatype-jsr310`，序列化 `LocalDateTime` 这类 Java 8 时间类型不需要额外加模块。这个细节挺有意思，工程里的 `Instant` 字段直接输出了 `2026-09-17T14:43:09.616177200Z`，我没加过任何时间模块，它就这么工作了。以前那种「时间序列化报错，搜一圈加个 jsr310」的流程，在这代里不用走了。

最后是错误响应。

SpringBoot 4 里配一行 `spring.mvc.problemdetails.enabled=true`，全局错误响应就统一成 RFC 9457 定义的 `application/problem+json` 格式：

```json
{
  "detail": "订单不存在：99999",
  "instance": "/api/orders/99999",
  "status": 404,
  "title": "资源不存在",
  "type": "urn:problem-type:resource-not-found"
}
```

五个字段各自语义明确。`type` 是稳定的错误类型标识，给程序做分支用。`title` 是给人看的短描述。`status` 就是 HTTP 状态码。`detail` 是这次具体错在哪。`instance` 是出错的请求路径，排障时特别好使。

`ProblemDetail` 的属性默认按字母序输出，实际响应里 `detail` 在前、`type` 在后，跟 RFC 文档展示的顺序不一样。解析响应不要依赖字段顺序，这是我拿真实响应和文档对了一遍才敢写下的结论。

有现成标准且框架直接支持，不必自己再造 `{code, msg, data}`。

## 依赖和工程骨架

pom 就三个依赖，很朴素。

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <!-- 参数校验 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <!-- Web 工程配套的测试 starter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

`application.yml` 关键就两处。

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
  jackson:
    default-property-inclusion: non_null
```

第二行让空值不出现在响应体里，是契约选择。第一行才是重点，ProblemDetail 全靠它。

这个骨架后面几篇会直接复用，MyBatis、JPA、MongoDB 那几篇只换仓储实现。所以包结构我多花了一点心思，六个包各管一摊：

```
controller/   资源入口，只做参数绑定与响应组装
service/      业务规则，状态机、订单号生成、终态保护
repository/   仓储接口 + 内存实现
domain/       领域模型、状态枚举、查询条件
dto/          请求体与响应体
exception/    异常定义与全局处理
```

![](https://static.xiongneng.me/layer-stack-20260921204934.png)

请求从 controller 进来一路向下，响应和异常往上冒。两个刻意的设计放在这里说。

一个是**领域模型用 `record` 做不可变**。订单对象一旦创建就不能改，要改只能生成一个新的。

```java
public record Order(
        Long id, String orderNo, String customerName, String customerPhone,
        BigDecimal totalAmount, OrderStatus status, String remark,
        Instant createdAt, Instant updatedAt
) {
    public Order withStatus(OrderStatus newStatus, Instant updatedAt) {
        return new Order(id, orderNo, customerName, customerPhone, totalAmount,
                newStatus, remark, createdAt, updatedAt);
    }
}
```

这么写的好处是，仓储层的「更新」变成整对象替换，读操作天然拿到某个一致快照，不会出现「读到一半的订单」。存储用 `ConcurrentHashMap` 存整对象，连锁都不用加。刚下笔时我嫌这么写拘谨，真写下来发现它把并发问题直接消掉了，值。

另一个是**响应体绝不复用领域模型**。这是接口契约稳定性的问题。

`OrderResponse` 里做了两件领域模型不该关心的事：手机号脱敏，以及把状态枚举翻译成中文描述。

```java
public static OrderResponse from(Order order) {
    return new OrderResponse(
            order.id(), order.orderNo(), order.customerName(),
            maskPhone(order.customerPhone()), order.totalAmount(),
            order.status(), order.status().getLabel(), order.remark(),
            order.createdAt(), order.updatedAt());
}

private static String maskPhone(String phone) {
    if (phone == null || phone.length() != 11) return phone;
    return phone.substring(0, 3) + "****" + phone.substring(7);
}
```

文章开头说的「别把内部结构泄漏出去」，还有个更常见的坑：直接返回 Spring Data 的 `Page`。

`Page` 序列化出来会带着十几个字段，`pageable`、`sort`、`first`、`last`、`numberOfElements`、`offset`，全是 Spring Data 的内部实现细节。这些字段一旦随框架版本变动，前端就得跟着改。工程里用了自己的 `PageResponse`，只保留 `list`、`page`、`size`、`total`、`totalPages` 五个字段。

## 核心代码

### 状态机得写在枚举里

订单状态不能随便改，`CREATED` 不能直接跳到 `COMPLETED`。这类规则最怕散落在各个 `if` 里，过两个月谁也不知道完整规则是什么，我在别人的老项目里吃过这个亏，所以这次换了写法。

做法是把流转规则收进枚举本身。

```java
public enum OrderStatus {
    CREATED("已创建"), PAID("已支付"), SHIPPED("已发货"),
    COMPLETED("已完成"), CANCELLED("已取消");

    private final String label;

    public boolean canTransferTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
```

`switch` 表达式在 Java 21 上写出来干净，新增状态时编译器会直接报错提醒补规则。这比散落的 `if` 强太多。

流转关系画出来是这样。

```
CREATED ──> PAID ──> SHIPPED ──> COMPLETED
   │          │
   └──────────┴──────> CANCELLED
```

![](https://static.xiongneng.me/order-state-machine-20260921204934.png)

`COMPLETED` 和 `CANCELLED` 是终态，不光不接受状态流转，也不允许通过 `PUT` 改任何业务字段。这条规则放在服务层的 `ensureModifiable` 里，实测返回 422。

### 入参用独立 DTO，客户端就改不了它不该改的东西

请求体如果用领域模型来接，客户端就能多传一个 `status` 字段，直接把订单改成已完成。这是很经典的越权漏洞，防它的成本极低，就是别拿领域模型接请求体。

工程里用独立的 `OrderCreateRequest`，字段是白名单，并且带上校验注解。

```java
public record OrderCreateRequest(
        @NotBlank(message = "客户姓名不能为空")
        @Size(max = 32, message = "客户姓名长度不能超过 32")
        String customerName,

        @NotBlank(message = "客户手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "客户手机号格式不正确")
        String customerPhone,

        @NotNull(message = "订单金额不能为空")
        @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "订单金额最多 10 位整数、2 位小数")
        BigDecimal totalAmount,

        @Size(max = 200, message = "备注长度不能超过 200")
        String remark
) {}
```

注意这里的 `message` 全是中文。很多项目把校验提示写成英文，然后在 `@ExceptionHandler` 里再翻译一遍，直接写中文就完了，校验框架会把 `message` 原样带进响应。

另外，`totalAmount` 用 `BigDecimal`。金额不要用 `double`，原因直接看 `0.1 + 0.2 != 0.3`，真金白银出错的案例并不少见。

### Controller 里的三个关键点

第一个是创建订单的 `Location` 头。

```java
@PostMapping
public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
    Order created = orderService.create(request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(OrderResponse.from(created));
}
```

`ResponseEntity.created(location)` 一次把状态码 201 和 Location 头都给了。为什么要在头里给出新资源的地址？因为客户端拿到响应后不需要自己拼 URL 就能直接访问这个订单。这就是 REST 里 HATEOAS 最朴素的那个意思。

实测回来的头是这样的。

```
HTTP/1.1 201
Location: http://127.0.0.1:18080/api/orders/1
Content-Type: application/json
```

第二个是状态流转，用 `PATCH` 子资源，路径是 `/api/orders/{id}/status`。`PATCH` 比 `PUT` 更准确，因为只改一个字段，不算整体替换。

![](https://static.xiongneng.me/status-transition-422-20260918125927.png)

第三个是分页查询的排序白名单，这是有实际教训的。

```java
public record OrderPageQuery(String keyword, OrderStatus status,
                             int page, int size, String sortBy, boolean descending) {

    public static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "orderNo", "customerName", "totalAmount",
                   "status", "createdAt", "updatedAt");

    public OrderPageQuery {
        if (sortBy == null || !SORTABLE_FIELDS.contains(sortBy)) {
            sortBy = "createdAt";
        }
        size = Math.clamp(size, 1, MAX_SIZE);
        page = Math.max(page, 0);
    }
}
```

不做白名单的后果：直接拿客户端传来的字段名去 `Comparator.comparing`，传一个 `sort=abc,desc`，程序就抛异常；传一个 `sort=password,desc`，在有些实现里甚至能变成一次信息探测。紧凑构造器里 `Math.clamp` 把 size 夹进合法区间，page 兜底到 0，凑合传进来的参数就不至于把接口打挂。

另外注意 `comparator` 里补了一个次排序键。

```java
comparator = comparator.thenComparing(Order::id);
return query.descending() ? comparator.reversed() : comparator;
```

如果只按 `createdAt` 排序，两条记录的时间戳恰好相同时，相对顺序在不同的请求里可能不稳定，翻页时会看到同一条数据出现两次、另一条漏掉。加上主键做次排序键，顺序就稳定了。这个问题本地数据量小的时候看不出来，上线了才暴露，属于最烦的那类。

### 全局异常处理为什么继承而不是从零写

这是最常见的写法错误，我单独拎出来讲。

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
```

常见的写法是一个空的 `@RestControllerAdvice` 加一个 `@ExceptionHandler(Exception.class)`。这么写的话，Spring MVC 内部抛出来的 405、415、400 这些异常，全都掉进 `Exception.class` 那个分支里，变成 500 返回。

`ResponseEntityExceptionHandler` 这个基类里已经把框架的几十种异常归类处理好了，每种都对应正确的状态码和响应格式。只需要覆盖关心的那几个，剩下的交给它。站在基类肩膀上，比从零收拾几十种异常类型划算得多。

工程里覆盖了三个，加上两个自定义异常的处理器。

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ProblemDetail> handleResourceNotFound(ResourceNotFoundException ex) {
    ProblemDetail problemDetail =
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problemDetail.setType(TYPE_RESOURCE_NOT_FOUND);
    problemDetail.setTitle("资源不存在");
    problemDetail.setProperty("resourceType", ex.getResourceType());
    problemDetail.setProperty("resourceId", ex.getResourceId());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
}
```

覆盖的三个分别是请求体校验失败、查询参数校验失败、请求体格式错误，作用都是把逐字段的错误信息整理进 `errors` 属性里。

`ProblemDetail` 有个 `setProperty` 方法，可以往标准五字段之外添加自定义属性。RFC 9457 明确允许，规范里说客户端应当忽略不认识的属性。所以 404 里可以塞 `resourceType` 和 `resourceId`，400 里可以塞 `errors` 数组，既符合标准，又多给了客户端有用的信息。

![](https://static.xiongneng.me/problem-detail-20260921204934.png)

## 完整案例，跑起来看

先起服务。

```bash
mvn clean package
java -jar target/springboot4-restful-1.0.0.jar
```

日志里看到这行就成功了。

```
Tomcat started on port 8080 (http) with context path '/'
Started RestfulApplication in 1.74 seconds
```

启动 1.74 秒，这是裸工程在我这台机器上的数，你那边有出入属正常。

下面这些是实际跑出来的响应，一个字没改。

**创建订单。**

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":299.50,"remark":"演示下单"}'
```

```http
HTTP/1.1 201
Location: http://localhost:8080/api/orders/1

{"id":1,"orderNo":"ORD2026091885919224","customerName":"熊大","customerPhone":"138****8000","totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单","createdAt":"2026-09-18T04:59:23.787512600Z","updatedAt":"2026-09-18T04:59:23.787512600Z"}
```

三个细节都在里面了，201 状态码、`Location` 头、脱敏后的手机号 `138****8000`。订单号是服务端生成的，客户端无权指定。

![](https://static.xiongneng.me/restful-order-created-201-20260918125925.png)

**非法状态流转，返回 422。**

```bash
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H "Content-Type: application/json" -d '{"status":"CREATED"}'
```

```http
HTTP/1.1 422
Content-Type: application/problem+json

{"detail":"订单不允许从 已支付 变更为 已创建","instance":"/api/orders/1/status","status":422,"title":"业务规则不满足","type":"urn:problem-type:business-rule-violation"}
```

`detail` 这句话把「从什么状态到什么状态」都带上了，运维半夜看日志能一眼看明白，不用再去翻代码。

**字段校验失败，返回 400，逐字段原因。**

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
    {"field": "customerPhone", "message": "客户手机号格式不正确"},
    {"field": "totalAmount", "message": "订单金额必须大于 0"},
    {"field": "customerName", "message": "客户姓名不能为空"}
  ]
}
```

这种结构前端直接拿 `errors` 循环渲染到表单下面就行了，不用再写一堆字符串匹配。

**未映射的方法，框架自己返回 405。**

```http
HTTP/1.1 405
Allow: POST, GET
Content-Type: application/problem+json

{"detail":"Method 'PATCH' is not supported.","instance":"/api/orders","status":405,"title":"Method Not Allowed"}
```

注意 `Allow` 头，框架直接给出这个路径支持哪些方法。客户端和调试工具都能自己读懂，不需要额外的文档说明。

总共跑了 12 个场景，状态码分别是 201、200、200、200、200、422、404、400、400、405、204、404，全部符合预期，应用日志里 ERROR 行数为 0。这串状态码我是对着终端记录一条条核过的，算得上铁证。

上面这些全是 curl 看到的。用浏览器直接打开接口呢？顺手开了一个不存在的订单截了图。

![](https://static.xiongneng.me/restful-browser-json-view-20260918125928.png)

Chrome 把 JSON 压成了一行，得点左上角那个「美观输出」才展开成分层结构。排查接口更推荐 curl，至少状态行和响应头能一起看见。

## 测试

`spring-boot-starter-webmvc-test` 带进来的两个东西，用起来挺舒服。

一个是 `MockMvcTester`，它保留了 MockMvc 的链式请求写法，断言换成 AssertJ。

```java
@Test
@DisplayName("分页查询：关键字过滤与每页条数生效，总页数按总数换算")
void pageFiltersByKeyword() {
    String tag = uniqueTag();
    createOrder(tag);
    createOrder(tag);
    createOrder(tag);

    AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
            .param("keyword", tag)
            .param("size", "2")
            .exchange()
            .assertThat()
            .hasStatusOk()
            .bodyJson();
    body.extractingPath("$.total").asNumber().isEqualTo(3);
    body.extractingPath("$.totalPages").asNumber().isEqualTo(2);
    body.extractingPath("$.list").asArray().hasSize(2);
}
```

`extractingPath` 直接按 JSON Path 断言，测试失败的时候响应体完整打出来，不用再自己解析字符串。

另一个是 `RestTestClient`。它能绑定真实服务端，也能绑定 MockMvc 或者单个 Controller，API 风格和 `RestClient` 一致。

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        client = builder.build();
    }
```

在真实 Tomcat 上跑完整生命周期，创建、查询、分页、流转、更新、删除，一条链下来。

```bash
mvn test
```

```
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

17 个用例，15 个是 MVC 切片测试，2 个是真实端口的端到端测试。两条路我都留着：切片测试跑得快，端到端测试兜真实行为的底，缺哪个心里都不踏实。

## 避坑指南

这部分是工程里实打实踩到的，按痛苦程度排序。

**坑一，控制器类上不要写 `@Validated`。**

这个坑最阴，我在它上面耗了半天。控制器上顺手加了 `@Validated`，测试里「size 超过上限应该返回 400」那条直接失败，实际返回的是 500。

Spring Framework 官方文档里写得很直白。

> If a controller has a class level `@Validated`, then method validation is applied through an AOP proxy. In order to take advantage of the Spring MVC built-in support for method validation added in Spring Framework 6.1, you need to remove the class level `@Validated` annotation from the controller.

类上一旦有 `@Validated`，方法校验就走 AOP 代理链路，抛 `ConstraintViolationException`。而内置校验抛的是 `HandlerMethodValidationException`，两者不是同一个异常，全局异常处理器里为 400 准备的那条分支不会命中，最后掉进兜底的 500 里。

这两个异常要同时处理。

> Applications should handle both `MethodArgumentNotValidException` and `HandlerMethodValidationException` since either may be raised depending on the controller method signature.

请求体上的 `@Valid` 校验的是单个对象，抛 `MethodArgumentNotValidException`；而 `@RequestParam` 上直接挂 `@Min`、`@Max` 这种约束注解，走的是方法级校验，抛 `HandlerMethodValidationException`。工程里两种都有，两个 `handle` 方法都覆盖了。你要是只处理了其中一个，漏掉的那种请求就会莫名其妙变 500。

**坑二，`@AutoConfigureMockMvc` 的包路径是 `org.springframework.boot.webmvc.test.autoconfigure`。**

写成 `org.springframework.boot.test.autoconfigure.web.servlet` 会找不到类，编译过不去。我看到一堆 `@WebMvcTest` 找不到的时候先查的是依赖，折腾了两轮才想起查 import；依赖加了没用，得改 import。

另外，`@SpringBootTest` 不再隐式启用 MockMvc，注入 `MockMvc` 必须显式写 `@AutoConfigureMockMvc`。

**坑三，Jackson 3 的包名前缀是 `tools.jackson`，注解模块除外。**

databind 和 core 的前缀是 `tools.jackson`，`jackson-annotations` 模块还在 `com.fasterxml.jackson.annotation` 包下。

所以 `@JsonProperty` 从 `com.fasterxml.jackson.annotation` 导入，而 `JsonMapper` 得从 `tools.jackson.databind.json` 导入。迁移指南里写明了这是官方设计。这个组合很反直觉，一个库两套包名，IDE 的自动 import 经常替你选错。

另外，Boot 4 自动配置的 `JsonMapper` 可以直接注入，测试里可以用它解析响应体。

```java
@Autowired
private JsonMapper jsonMapper;
```

**坑四，`Location` 头给的是绝对地址，反代部署要注意。**

`ServletUriComponentsBuilder.fromCurrentRequest()` 出来的路径实测是 `http://127.0.0.1:18080/api/orders/1`，绝对的。

直连没问题；挂到 Nginx 或网关后面时，容器里看到的是内网地址和内网协议，不对 `X-Forwarded-*` 头做处理，`Location` 会给出一个客户端访问不到的地址。

有现成的配置，加一行就行。

```yaml
server:
  forward-headers-strategy: native
```

**坑五，重复删除返回了空响应体。**

删除接口最初这么写。

```java
return orderService.delete(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
```

第一次删返回 204，没问题。第二次删返回 404，状态码也对。但响应体是空的，而其它所有接口的 404 都是 `application/problem+json`。

12 个场景跑完回头核对才发现，同一个状态码有两种响应格式，客户端得写两套解析逻辑。这也是我坚持把每个响应逐条抄下来的原因：自动化测试没覆盖到的地方，肉眼核对兜住了。

改成让服务层直接抛 `ResourceNotFoundException`，走统一的异常处理，格式就一致了。

```java
public void delete(Long id) {
    if (!orderRepository.deleteById(id)) {
        throw new ResourceNotFoundException("订单", id);
    }
}
```

这个坑的教训：接口的一致性靠对着实际响应一条条核对。自动化测试能守住，前提是先想到要测这个。

**坑六，别把 `Page` 直接返回给前端。**

上面提过一次，再强调一遍。`Page` 序列化出来带着 `pageable`、`sort`、`first`、`last`、`offset` 这些 Spring Data 实现细节字段，会随框架升级悄悄变化，接口契约跟着变。

**坑七，排序字段和分页顺序这两个细节。**

排序字段不白名单，轻则异常，重则信息泄漏。分页不加次排序键，会出现翻页时数据重复或漏项。这两个都是「上线了才被发现」的问题，本地测试数据量小的时候看不出来。

## 小结

这个主题的当前写法整理成一张表，新工程照着抄就行。

| 项目 | 当前写法 |
|---|---|
| Web starter | `spring-boot-starter-webmvc` |
| 测试依赖 | `spring-boot-starter-webmvc-test` |
| MockMvc 注解包 | `org.springframework.boot.webmvc.test.autoconfigure` |
| MockMvc 启用 | `@SpringBootTest` 之外显式加 `@AutoConfigureMockMvc` |
| 控制器校验 | 类上不写 `@Validated`，用内置方法校验 |
| JSON 包名 | `tools.jackson`（注解模块除外） |
| 错误响应 | RFC 9457 ProblemDetail |
| 422 状态码常量 | `UNPROCESSABLE_CONTENT` |

最后那行解释一下：RFC 9110 把 422 的语义描述从 entity 换成了 content，`HttpStatus` 里对应的常量名是 `UNPROCESSABLE_CONTENT`。状态码本身还是 422；按 `UNPROCESSABLE_ENTITY` 这个名字引用常量，编译会报错。我第一次写就报了，查了半天才发现常量改了名，闹了个小笑话。

要说选型建议，我的排序是：新工程直接用拆分后的 starter，别背 `classic` 聚合包；错误响应从第一天就开 ProblemDetail，别等前端对接完了再改格式；控制器类上永远不挂 `@Validated`；响应体和入参 DTO 都不复用领域模型。这几件事当下成本都极低，收益全是长期的，属于稳稳的赚。

没解决的事也直说：`OrderRepository` 现在只有内存实现，数据进程重启就清空，这是刻意的，让工程不依赖任何外部中间件就能跑起来。但这个接口的形状能不能原样扛住 MyBatis、JPA 那类真实现，分页和排序的契约要不要跟着调，我还没验证，这是这套骨架眼下最悬的一处。

## 参考链接

- [Spring Boot 4.1 Reference - Web MVC](https://docs.spring.io/spring-boot/4.1/reference/web/index.html)
- [Spring Framework 7.0 - Annotation Controller 配置](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods.html)
- [RFC 9457 - Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [Jakarta Bean Validation 3.1 规范](https://jakarta.ee/specifications/bean-validation/3.1/)
- [Spring Boot 4.1 Reference - Testing Spring MVC 切片测试](https://docs.spring.io/spring-boot/4.1/reference/testing/index.html)
