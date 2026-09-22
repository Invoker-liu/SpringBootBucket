---
title: SpringBoot4系列22 - 使用API版本管理
slug: sb4-apiversion
date: 2026-09-21 10:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, API版本, SpringMVC, 接口设计 ]
draft: false
---

先讲一段我自己的亲身经历。订单服务跑到第二年，产品要在新版 App 里把金额拆开摆：商品金额、折扣、运费各自一行。我把 `/api/orders/{id}` 的响应体重构了一遍，customerName 与 customerPhone 聚成 customer 对象，金额拆成 items 数组加 amount 子结构，自测没问题就发了版。上线十分钟，财务的对账系统报警：它按字段平铺解析订单 JSON，customer 对象它不认识，整批对账任务全挂。回滚、发版、写死一套字段映射兼容代码，前前后后折腾了我一周。

这次事故让我认了一个理：毛病出在响应体只有一个版本。老客户端认旧结构，新客户端要新结构，同一个路径上必须同时服务两套契约，由请求方声明自己说的是哪种语言。API 版本管理就是解决这件事的机制：请求带上版本号，服务端按版本把同一个 URL 路由到不同的响应结构，业务与数据层只有一份。这篇文章是我在 Spring Boot 4.1.1 上把这套机制落地的完整记录：Framework 7.0.9 的版本化路由加 Boot 4.1.1 的 spring.mvc.apiversion 属性，v1 与 v2 两套 controller 并存，header 与 query 两条解析路线我都实测过，默认版本、非法版本、版本范围三种边界行为逐个验证，8 个端到端用例全绿，全部数字来自同一次运行，我留了底。

![](https://static.xiongneng.me/apiversion-sequence-flow-20260922053205.png)

## 编程模型与事实清单

动笔前我把相关制品全部解包核对过：spring-web 7.0.9、spring-webmvc 7.0.9、spring-boot-webmvc 4.1.1，下面这些事实都来自制品本身和真实运行，不是我猜的。

第一个事实直接决定代码怎么写：**Framework 7 里没有 @ApiVersion 注解**。spring-web、spring-webmvc、spring-context、spring-core 四个 jar 我全量检索过，ApiVersion.class 命中 0 次。版本声明是映射注解自带的一个属性，javap 反编译 @RequestMapping 可以看到第 9 个属性：

```java
public @interface RequestMapping {
    String name();
    String[] value();
    String[] path();
    RequestMethod[] method();
    String[] params();
    String[] headers();
    String[] consumes();
    String[] produces();
    String version();          // 版本声明在这里
}
```

@GetMapping、@PostMapping 这些组合注解都继承这组属性，所以 controller 上直接写 `@GetMapping(value = "/orders", version = "1")` 就行，不需要另贴注解。version 的值支持两种写法：`"2"` 表示精确匹配版本 2，`"2+"` 表示 2 与更高的版本都归这个方法。说实话我第一次看到这个设计还挺意外，版本能力居然藏在每个映射注解里，而不是一个独立的开关，后面你会看到这个选择带来的好处。

第二个事实是版本解析的扩展点位置。相关类型集中在 org.springframework.web.accept 包（spring-web 7.0.9 实证）：

```text
ApiVersionResolver            接口：String resolveVersion(HttpServletRequest)
HeaderApiVersionResolver      从请求头取版本
QueryApiVersionResolver       从查询参数取版本
MediaTypeParamApiVersionResolver  从 media type 参数取版本
PathApiVersionResolver        从路径段取版本
ApiVersionStrategy            接口：解析、校验、默认版本、废弃提示的总入口
DefaultApiVersionStrategy     唯一内置实现
ApiVersionParser              接口：字符串转版本对象
SemanticApiVersionParser      默认实现，产出 Version(major, minor, patch)
ApiVersionHolder              每个请求的版本载体，挂在 request attribute 上
ApiVersionDeprecationHandler  版本废弃提示的回调接口
MissingApiVersionException    无版本且必填时抛出
InvalidApiVersionException    版本非法时抛出，NotAcceptableApiVersionException 继承它
```

路由侧还有一个类：VersionRequestCondition 在 org.springframework.web.servlet.mvc.condition 包，它负责「请求版本与映射版本的比对」。controller 方法参数还可以直接声明 SemanticApiVersionParser.Version 类型拿到解析结果，由 ApiVersionMethodArgumentResolver 注入（org.springframework.web.servlet.mvc.method.annotation 包）。

第三个事实是 Boot 侧的属性装配。spring-boot-webmvc 4.1.1 的 WebMvcProperties 里有个 Apiversion 嵌套类，包名 org.springframework.boot.webmvc.autoconfigure，对应 spring.mvc.apiversion 前缀。全部 8 条属性我逐条核对过 configuration metadata（均无默认值）：

```text
spring.mvc.apiversion.default                    String    无版本请求按这个版本处理
spring.mvc.apiversion.required                   Boolean   每个请求是否必须带版本
spring.mvc.apiversion.supported                  List<String>  受支持的版本白名单
spring.mvc.apiversion.detect-supported           Boolean   是否从 controller 映射探测受支持版本
spring.mvc.apiversion.use.header                 String    用指定名字的请求头取版本
spring.mvc.apiversion.use.query-parameter        String    用指定名字的查询参数取版本
spring.mvc.apiversion.use.media-type-parameter   Map<MediaType, String>  用 media type 参数取版本
spring.mvc.apiversion.use.path-segment           Integer   用指定下标的路径段取版本
```

装配链路是这样的：WebMvcAutoConfiguration 的内部适配器读取这组属性，逐条调到 ApiVersionConfigurer 上（org.springframework.web.servlet.config.annotation 包，提供 useRequestHeader、useQueryParam、setDefaultVersion、addSupportedVersions 等方法），最终构建出 DefaultApiVersionStrategy。你在工程里声明为 bean 的 ApiVersionResolver、ApiVersionParser、ApiVersionDeprecationHandler 也会被一并收集进去，自定义解析路线与内置属性可以混用。还有一个容易忽略的点：属性清单里没有 enabled 开关，配了 use.* 或显式实现 WebMvcConfigurer 的 configureApiVersioning，版本化路由才会生效。

![](https://static.xiongneng.me/apiversion-config-wiring-20260922053041.png)

## 依赖与配置

依赖零新增，这点我很满意。API 版本能力全部在 spring-boot-starter-webmvc 带入的 spring-web 与 spring-webmvc 里，测试用 webmvc 专属测试 starter：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

版本号全部交给 spring-boot-starter-parent 4.1.1 的 BOM 管理，pom 里一个版本都不出现。我对比过第 1 篇 restful 工程的依赖面，两边完全一致，接了版本管理的工程没有多出任何 jar。

application.yml 是本篇的主角，三条配置决定全部行为：

```yaml
server:
  port: ${SERVER_PORT:18220}

spring:
  application:
    name: springboot4-apiversion
  mvc:
    problemdetails:
      enabled: true
    apiversion:
      default: 1
      required: false
      supported: 1, 2, 3
      use:
        header: X-Api-Version
        query-parameter: api-version

logging:
  level:
    com.xncoding.apiversion: DEBUG
```

`use.header` 与 `use.query-parameter` 同时配置时，框架按顺序尝试两条路线，任一路线取到版本即停。`default: 1` 声明无版本请求按 v1 处理，`required: false` 允许版本缺席。这两条必须成对出现，单独配 default 会出什么幺蛾子，我在避坑指南里给你展开。`supported: 1, 2, 3` 声明白名单，校验规则我在实测小节逐条验证过。

## 核心代码：同路径两套 controller

订单领域模型与业务服务只有一份。Order 持有全部字段，OrderService 提供查询与建单，两个版本的 controller 都调它：

```java
@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public Order getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + id));
    }

    public List<Order> findAll() {
        return repository.findAll();
    }
}
```

版本分叉停在这一层的上面。v1 与 v2 的差异全部用 DTO 表达：v1 是完整快照，字段平铺；v2 把客户聚成对象、金额拆成明细。service 与 repository 对版本一无所知，订单的存储、计算、状态流转只有一套逻辑。

v1 controller 用类级 @RequestMapping 声明路径与版本，方法级 @GetMapping 只管子路径：

```java
@RestController
@RequestMapping(value = "/api/orders", version = "1")
public class OrderControllerV1 {

    private final OrderService service;

    public OrderControllerV1(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public OrderResponseV1 detail(@PathVariable Long id) {
        return toDto(service.getById(id));
    }

    static OrderResponseV1 toDto(Order order) {
        return OrderResponseV1.of(order.getId(), order.getOrderNo(),
                order.getCustomerName(), order.getCustomerPhone(),
                order.getUnitPrice(), order.getQuantity(), order.getDiscount(),
                order.getShippingFee(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
```

v2 controller 与 v1 的差别只有两处：version 属性换成 "2"，DTO 换成 OrderResponseV2。路径 `/api/orders` 完全相同：

```java
@RestController
@RequestMapping(value = "/api/orders", version = "2")
public class OrderControllerV2 {

    private final OrderService service;

    public OrderControllerV2(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public OrderResponseV2 detail(@PathVariable Long id) {
        return toDto(service.getById(id));
    }

    static OrderResponseV2 toDto(Order order) {
        return OrderResponseV2.of(order.getId(), order.getOrderNo(),
                order.getCustomerName(), order.getCustomerPhone(),
                order.getUnitPrice(), order.getQuantity(), order.getDiscount(),
                order.getShippingFee(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
```

到这里你先记住一个结论：**版本只影响路由，不影响业务**。两个 controller 是两扇门，门后的房间相同。我把版本分叉点停在接口层，理由很实际：业务规则一旦按版本分叉，两个版本会各自演化出独立的缺陷与修复节奏，每加一个功能要写两遍；DTO 层的分叉成本只是多一个映射方法，字段差异被限制在序列化边界上。开头那段财务对账系统的诉求，靠 v1 DTO 原样保留就能满足，对账那边的代码一行不用动。

两个 DTO 的形状对比着看。v2 在 v1 的基础上聚了客户、拆了金额，内部结构三层：

```java
public class OrderResponseV2 {

    private Long id;
    private String orderNo;
    private Customer customer;      // 客户聚合：name + phone
    private List<AmountItem> items; // 金额明细：三行 label + value
    private Amount amount;          // 金额汇总：四个分量

    public static class Customer {
        private String name;
        private String phone;
    }

    public static class AmountItem {
        private String label;
        private BigDecimal value;
    }

    public static class Amount {
        private BigDecimal goodsAmount;
        private BigDecimal discountAmount;
        private BigDecimal shippingFee;
        private BigDecimal totalAmount;
    }
}
```

Customer、AmountItem、Amount 三个静态内部类只被 v2 响应体引用，v1 完全不知道它们的存在。明细行的 label（折扣优惠、运费）由 toDto 映射方法组装，客户端不需要自己拿单价乘数量再算折扣，对账口径由服务端统一给出。新版 App 要的「金额构成三行摆开」，就是 items 数组里那三条。

![](https://static.xiongneng.me/apiversion-class-diagram-20260922053215.png)

## 版本范围与版本注入

v2 之后如果还要出 v3、v4，响应结构不变，只是内部实现调整，逐个建新 controller 就没必要了。version 属性支持基线写法，`"2+"` 表示 2 与更高的版本都路由到这个方法：

```java
@RestController
public class VersionProbeController {

    @GetMapping(value = "/api/version/echo", version = "2+")
    public Map<String, Object> echo(Version version) {
        return Map.of(
                "resolved", version.toString(),
                "major", version.getMajor(),
                "minor", version.getMinor(),
                "patch", version.getPatch()
        );
    }
}
```

方法参数 Version 来自 SemanticApiVersionParser 的内部类，框架把它解析好的版本对象直接注入，Version 实现了 Comparable，major、minor、patch 三个分量可以比较。这个端点我在后面的实测里当探针用：请求版本 2 和 3 都会落进来，回显解析结果。类型全名是 org.springframework.web.accept.SemanticApiVersionParser.Version，想自定义版本语法就实现自己的 ApiVersionParser 替换默认解析器，注入参数的类型跟着自定义解析器走。

一个请求进来，版本的处理分两道闸，这个次序我建议你记牢，排错时全靠它。第一道是准入校验，发生在路由开始前：按配置的路线解析出版本字符串，SemanticApiVersionParser 把它转成 major.minor.patch 三元组，版本 1 按 1.0.0 处理；解析失败抛 InvalidApiVersionException，解析成功再查白名单（supported 集合与 controller 映射探测到的集合），查不到同样抛。第二道是路由匹配，VersionRequestCondition 拿请求版本与映射声明的版本比对：基线写法比较大小，精确写法在命中后还有一次相等确认，请求 3 打映射 2 的接口就是在这里被拦下，报出 7 号场景的 400。两道闸都过，请求才进 controller。理解了这次序，5、6、7 三个场景的区别就清楚了：5 号死在解析，6 号死在白名单，7 号死在路由确认。

## 完整案例：一次运行的全部行为

验证脚本按 12 个场景发请求，应用起在 18220，结果我逐条实录。先看正常路由：

```text
1. 不带任何版本        → 200  v1 快照结构（default: 1 生效）
2. X-Api-Version: 1    → 200  v1 快照结构
3. X-Api-Version: 2    → 200  v2 拆分结构
4. ?api-version=2      → 200  v2 拆分结构（与 header 等价）
```

v1 与 v2 的响应体实录如下，订单号与总金额两侧一致，结构与字段名互斥。v1 的完整快照：

```json
{"createdAt":"2026-09-20T10:30:00","customerName":"张三","customerPhone":"13800138000",
 "discount":0.90,"id":1,"orderNo":"SO-2026-0001","quantity":2,"shippingFee":8.00,
 "status":"CREATED","totalAmount":241.8200,"unitPrice":129.90}
```

v2 把客户聚成 customer 对象，金额拆成 items 数组与 amount 子结构：

```json
{"amount":{"goodsAmount":259.80,"discountAmount":25.9800,"shippingFee":8.00,
           "totalAmount":241.8200},
 "createdAt":"2026-09-20T10:30:00",
 "customer":{"name":"张三","phone":"13800138000"},
 "id":1,
 "items":[{"label":"单价 x 数量","value":259.80},
          {"label":"折扣优惠","value":25.9800},
          {"label":"运费","value":8.00}],
 "orderNo":"SO-2026-0001","status":"CREATED"}
```

对账系统只认 v1 的平铺结构，新版 App 消费 v2 的明细结构，两条请求打到同一个 URL，落进不同的 controller，取出来的是同一行数据。这就是我想要的那个效果，也是当初折腾一周想一次性解决的问题。

![](https://static.xiongneng.me/apiversion-response-panels-20260920150453.png)

错误路径的实测结果：

```text
5. X-Api-Version: abc       → 400  {"detail":"Invalid API version: 'abc'.", "status":400}
6. X-Api-Version: 9         → 400  {"detail":"Invalid API version: '9.0.0'.", "status":400}
7. X-Api-Version: 3 打订单   → 400  {"detail":"Invalid API version: '3.0.0'.", "status":400}
```

三条 400 的响应体都是 RFC 9457 problem+json（problemdetails.enabled 开启的效果）。abc 是解析失败，9 是白名单外的合法语义版本。3 号挺有意思，值得单独说：supported 里明明声明了它，但订单接口只映射了 1 和 2，框架校验发现版本 3 没有任何映射愿意接受，按非法版本处理。我第一眼看到这个结果也愣了一下，反应过来之后就服气了：白名单管准入，映射管路由，两道闸各管各的。

版本范围的探针结果：

```text
8. X-Api-Version: 3 打 echo（2+）   → 200  {"resolved":"3.0.0","major":3,"minor":0,"patch":0}
9. X-Api-Version: 2 打 echo（2+）   → 200
10. X-Api-Version: 2.4.1 打 echo    → 400  白名单精确匹配，小版本不自动包含
11. X-Api-Version: 1 打 echo（2+）  → 404  不满足基线，且无其他映射
```

10 号结果是反直觉的，单独拎出来讲。`"2+"` 字面上像是「2 以上全收」，实测 2.4.1 吃了 400。我的判断是：`2+` 的匹配范围受白名单约束，supported 里声明的是 `1, 2, 3` 三个精确值，2.4.1 不在集合内，校验先行拒绝。**白名单是精确集合匹配，不是区间**。想让 2.4.1 这类版本通过，要么把它列进 supported，要么用 ApiVersionConfigurer 的 setSupportedVersionPredicate 换成区间判断。

12 个场景的行为汇总成路由表：

| 请求版本 | 订单接口（1、2） | echo 探针（2+） |
|---|---|---|
| 无版本 | v1（default 生效） | 404，default 1 不满足 2+ |
| 1 | v1 快照结构 | 404 |
| 2 | v2 拆分结构 | 200，回显 2.0.0 |
| 3 | 400，白名单内无映射 | 200，回显 3.0.0 |
| 2.4.1 | 400，白名单外 | 400，白名单外 |
| abc / 9 | 400，解析失败或白名单外 | 400，同左 |

![](https://static.xiongneng.me/apiversion-dto-split-20260922053045.png)

## 需要下线时：弃用通知与自定义解析

版本管理落地后马上会遇到的下一个需求是下线 v1：老客户端不会主动迁移，服务端得有办法把「这个版本要废弃了」的信号带给调用方。Framework 7 内置了标准实现，声明一个 bean 就接上，我照官方的做法配了一份：

```java
@Configuration
public class ApiVersionDeprecationConfig {

    @Bean
    public ApiVersionDeprecationHandler apiVersionDeprecationHandler() {
        StandardApiVersionDeprecationHandler handler =
                new StandardApiVersionDeprecationHandler();
        handler.configureVersion("1")
                .setDeprecationDate(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0,
                        ZoneId.of("Asia/Shanghai")))
                .setDeprecationLink(URI.create(
                        "https://example.com/docs/api-v2-migration"))
                .setSunsetDate(ZonedDateTime.of(2027, 4, 1, 0, 0, 0, 0,
                        ZoneId.of("Asia/Shanghai")));
        return handler;
    }
}
```

configureVersion 的返回值是 VersionSpec，弃用日期、迁移文档、下线日期都在它上面链式设置，返回值类型是 ApiVersionDeprecationHandler 的 handler 本体，这一段的收尾要单独 return（链式一路点到底编译不过，VersionSpec 不继承 handler）。这里我提醒一句，我差点就顺着链式写法一路点下去，IDE 没报错是因为方法返回的确实是 handler 本身，真正编译报错的位置在下一行。Boot 的自动配置用 ObjectProvider 收集这个 bean，挂进 DefaultApiVersionStrategy。

实测效果，请求带 `X-Api-Version: 1` 时响应头多出三行：

```text
HTTP/1.1 200
Deprecation: @1790784000
Link: <https://example.com/docs/api-v2-migration>; rel="deprecation"; type="text/html"
Sunset: Thu, 1 Apr 2027 00:00:00 +0800
```

三个头的格式来自 RFC 9745（Deprecation header field）与 RFC 8594（Sunset header field），Deprecation 是 Unix 时间戳，Sunset 是 HTTP 日期。同一个请求换成 `X-Api-Version: 2`，三个头全部消失，弃用信号只发给还在用 v1 的客户端。这个设计我觉得很聪明：监控这类的响应头，可以精确统计 v1 的存量流量，下线窗口的决策依据就有了。

版本插入位置还有一种场景：客户端不方便加请求头时，版本也可以放在路径段或 media type 参数里。use.path-segment 配一个整数下标，`/api/1/orders` 这种 URL 里的 1 就成了版本；use.media-type-parameter 接一个 Map，`Accept: application/json;version=2` 也能解析。四条 use.* 路线可以并存，框架按声明顺序逐条尝试。自定义程度更高的需求，比如版本号写在签名里、要从数据库读映射关系，实现 ApiVersionResolver 接口声明成 bean 即可，方法签名只有一个：String resolveVersion(HttpServletRequest)，返回 null 表示该路线未取到版本，交给下一条路线。

## 测试怎么写

8 个用例端到端跑真实 Tomcat，RestTestClient 断言状态码与响应体：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiVersionEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void headerRouteSelectsVersion() {
        String v1 = client.get().uri("/api/orders/1")
                .header("X-Api-Version", "1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatusCode.valueOf(200))
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(v1).contains("customerPhone").contains("unitPrice");

        String v2 = client.get().uri("/api/orders/1")
                .header("X-Api-Version", "2")
                .exchange()
                .expectStatus().isEqualTo(HttpStatusCode.valueOf(200))
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(v2).contains("\"customer\":{\"name\"").contains("\"items\"");
    }
}
```

headerRouteSelectsVersion 断言两件事：同一路径上版本 1 落 v1 结构、版本 2 落 v2 结构，两边字段名互斥。另外 7 个用例分别覆盖默认版本回落、query 路线、非法版本 400、白名单外 400、白名单内无映射 400、范围匹配四种组合、两版本列表并存。

mvn test 一次跑完的 surefire 对账：

```text
ApiVersionEndToEndTest    8    4.062s    默认版本 / 双路线路由 / 白名单 / 范围 / 并存
合计                      8    BUILD SUCCESS
```

应用日志 ERROR 0 行。全部数字来自同一次运行，原始记录我留了底，你需要复核随时能对上。

## 避坑指南

**坑一，default 与 required 必须成对出现。** 我在这里栽过：只配 `spring.mvc.apiversion.default: 1` 时，不带版本的请求返回 400 而非落到 v1，required 缺省按必填处理，无版本直接 MissingApiVersionException。要默认版本回落，`required: false` 加 `default: 1` 两条一起写。

**坑二，required=false 且没有 default 时，无版本请求会命中所有版本映射。** 框架对无版本请求直接放行版本条件，v1 与 v2 两个 controller 同时成为候选，实际落点由排序决定，我实测落到 v2。这种配置等于把版本契约交给调度器掷骰子，required=false 必须配 default。

**坑三，属性名是 default，不是 default-version。** configuration metadata 里这条属性的名字是 spring.mvc.apiversion.default，写成 default-version 不报错、不生效，无版本请求的行为与没配一样。属性名以 spring-configuration-metadata.json 为准，IDE 补全比记忆可靠。

**坑四，白名单是精确匹配，小版本不会自动包含。** supported 声明 `1, 2, 3` 后，请求 2.4.1 得到 400，请求 3 反而通过校验。语义版本比较只发生在路由择优阶段，准入校验是集合 contains。发小版本前先把 supported 补齐。

**坑五，`2+` 基线端点同样受白名单约束。** 基线写法解决的是「不必为每个版本建 controller」，准入校验仍在白名单层面。新版本上线前，supported 里没有它，请求它就是 400，哪怕基线端点写得再宽。

**坑六，白名单内无映射的版本返回 400 而非 404。** 实测版本 3 打订单接口（映射只有 1 和 2）得到 400 InvalidApiVersionException，原因在路由次序：校验阶段 3 在白名单内放行，路由阶段精确映射不匹配，按非法版本兜底。排错时看到 400 先查 supported 与映射声明是否一致。

**坑七，宿主环境的 SERVER_PORT 会覆盖 yml。** 我本机环境注入过 SERVER_PORT 变量，`server.port: ${SERVER_PORT:18220}` 的写法让它优先于 yml 生效，应用起在别人的端口上，我还纳闷了一阵接口怎么不通。启动前 export SERVER_PORT=18220 显式固定，或者命令行加 --server.port 参数。

**坑八，版本分叉别越过接口层。** v1 与 v2 共用 OrderService 与 OrderRepository，业务逻辑一处维护。把版本判断写进 service（if 版本为 2 则走新逻辑），两套演化线会互相缠绕，DTO 映射层多一个类就能解决的问题，不要带进业务层。

## 小结

版本管理这件事，我的选型建议只有一条主线：把版本当路由问题处理，别当业务问题处理。controller 层声明 version，Boot 侧用 spring.mvc.apiversion 八条属性管解析路线、默认版本和白名单，业务与数据层保持零感知。header 与 query 两条路线按你的客户端形态挑一条即可，存量调用方加不了请求头就换 path-segment，新版本不想建 controller 就用 `2+` 基线，都是属性层面的事。唯一要上心的是白名单语义：它是精确集合，发小版本前记得补 supported。

没解决的事也直说：废弃提示只覆盖了「告诉调用方要下线」这一步，v1 真正摘除时的流量切 Ambassador、老客户端版本分布的采集策略，这两块我没在工程里落地。等我把 v1 的存量流量统计跑起来，再补一篇下线实操。

## 参考链接

- [Spring Framework Web MVC 文档](https://docs.spring.io/spring-framework/reference/web/webmvc.html)：API versioning 与 ApiVersionConfigurer 的权威说明
- [Spring Boot Web MVC 特性文档](https://docs.spring.io/spring-boot/reference/web/servlet.html)：spring.mvc.apiversion 属性与自动配置说明
- [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)：错误响应体的规范格式
- [Spring Boot 4.1.1 API](https://docs.spring.io/spring-boot/api/java/index.html)：WebMvcProperties.Apiversion 的 Javadoc
- [spring-web 7.0.9（Maven Central）](https://repo1.maven.org/maven2/org/springframework/spring-web/7.0.9/)：本文版本结论的制品实证来源
