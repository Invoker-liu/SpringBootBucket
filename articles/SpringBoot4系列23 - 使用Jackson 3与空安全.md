---
title: SpringBoot4系列23 - 使用Jackson 3与空安全
slug: sb4-jackson3
date: 2026-09-20 16:30:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Jackson, JSON, 空安全 ]
draft: false
---

前段时间我帮订单服务做过一次对账排查：财务那边的解析脚本按「字段有值才算有效记录」的口径读订单 JSON。游客单没留电话、没用优惠券、没填备注，响应体里这些字段带着 null 原样输出，对账脚本把整批订单判成无效数据，夜里重跑了三次，我第二天早上才看到告警。另一件事更隐蔽：金额字段 discountCents 在下单链路里偶尔是 null，对账方拿到 null 按数值 0 参与汇总，月初的折扣报表少算了两万块，排查的时候我才搞明白，null 与 0 在他们的口径里是两个东西，这么混着用迟早闹笑话。

这两件事指向同一个机制：JSON 序列化时对空值的处理策略。空字段省略还是输出 null、请求体里的 null 落成什么值、null 打进 primitive 怎么办，这些决策都写死在框架默认行为里，我不显式声明，框架就替业务做主。所以我把 Jackson 3 的空值处理在 Spring Boot 4.1.1 上完整实测了一遍：默认行为取证、三种空值策略同场对比、自定义序列化器做金额分转元、反序列化容错逐项验证，11 个端到端用例全绿，全部数字来自同一次运行，原始记录我都留了底。


## 包名与坐标事实清单

动笔前我把相关制品全部解包核对了一遍：tools.jackson.core:jackson-databind 3.1.5、jackson-core 3.1.5、jackson-annotations 2.21、spring-boot-jackson 4.1.1，下面这些事实都来自制品与我自己的真实运行。

第一个事实是坐标。Jackson 3 的 Maven groupId 换成了 tools.jackson.core，Java 包名前缀跟着换成 tools.jackson，Boot 4.1.1 的 BOM 管理的小版本是 3.1.5，pom 里一个版本号都不用写，我一开始还想手动指定，后来发现纯属多余。注解是个例外：jackson-annotations 的 groupId 还是 com.fasterxml.jackson.core，版本线还是 2.21，包名也还是 com.fasterxml.jackson.annotation。这不是残留，databind 3 的代码大量引用这个包里的注解，它是两个包名世界之间的一座桥。

第二个事实是类名。构建 mapper 的推荐入口在 tools.jackson.databind.json 包：

```java
public class JsonMapper extends ObjectMapper {
    public static JsonMapper.Builder builder();
    public static JsonMapper shared();
    public static JsonMapper.Builder builderWithJackson2Defaults();
}
```

ObjectMapper 本身仍是可实例化的类，JsonMapper 继承它并补上 JSON 专属的工厂方法。异常体系在 Jackson 3 里全部改成了非受检：JacksonException 继承 RuntimeException，DatabindException 继承 JacksonException，序列化与反序列化方法签名里不再出现 throws。我第一次写自定义序列化器时顺手在方法上写了 throws，编译器当场给我上了一课。自定义序列化器的基类也换了名字，叫做 ValueSerializer 与 ValueDeserializer，方法参数里的上下文类型是 SerializationContext 与 DeserializationContext。

第三个事实是注解的归属，我把它们整理成一张当前事实清单：

```text
com.fasterxml.jackson.annotation 包（不变）
  @JsonInclude / @JsonSetter / @Nulls
  @JsonIgnore / @JsonProperty / @JsonAlias / @JsonFormat / @JsonCreator
tools.jackson.databind.annotation 包（databind 注解的新家）
  @JsonSerialize / @JsonDeserialize / @JsonNaming / @JsonPOJOBuilder
```

常用的 null 处理注解全在 com.fasterxml.jackson.annotation 包里，写法与记忆一致；需要指定自定义序列化器时，@JsonSerialize 与 @JsonDeserialize 在 tools.jackson.databind.annotation 包下，import 的时候认准路径。

空安全主题里两个注解的取值集合同样值得列成清单，签名我都在 javap 里核对过：

```text
@JsonInclude.Include 六个值
  ALWAYS        默认值：null 照常输出
  NON_NULL      null 不输出（本篇主角）
  NON_ABSENT    null 与「缺席值」（Optional.empty）都不输出
  NON_EMPTY     在 NON_ABSENT 基础上再省空字符串、空集合、空 Map
  CUSTOM        按 valueFilter/contentFilter 指定的过滤类判定
  USE_DEFAULTS  回退到类级或全局配置，解决字段与类注解冲突时的显式表态

@JsonSetter 的 Nulls 五个值（反序列化方向）
  SET          null 照常赋给字段
  SKIP         跳过这条 JSON 键，字段保持原值
  FAIL         抛 InvalidNullException，异常携带字段名（PropertyName）
  AS_EMPTY     落成类型的空值：String 为空串、数值为 0、集合为空集合
  DEFAULT      回退到全局默认行为
```

序列化侧的 NON_NULL 与反序列化侧的 AS_EMPTY 是一对常被混用的取值：前者管「出去时省略」，后者管「进来时补空值」，方向相反，语义也不同，我后文的实测会把这层差异摆出来。

![](https://static.xiongneng.me/jackson3-package-fact-tree-20260922055525.png)

## 依赖与配置

依赖零新增，这是我这次实测里最省心的一环。starter-webmvc 自带 Jackson 3，传递链是 spring-boot-starter-webmvc → spring-boot-starter-jackson → spring-boot-jackson → tools.jackson.core:jackson-databind 3.1.5，我跑了一遍 dependency:tree，没有任何可选件缺席：

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

单模块只想用 JSON 不起 Web 时，坐标是 spring-boot-starter-jackson。没有 starter-json 这个名字，我在官方文档里翻了半天才确认，九成的人第一次都会在这里扑空。

application.yml 里与 Jackson 相关的配置只有端口和日志，全局空值策略我故意留成注释，后面的对比实测会把它打开：

```yaml
server:
  port: ${SERVER_PORT:18230}

spring:
  application:
    name: springboot4-jackson3
  jackson:
    # default-property-inclusion: non_null

logging:
  level:
    com.xncoding.jackson3: DEBUG
```

spring.jackson 命名空间在 Boot 4.1.1 共有 34 条属性，这是我从 configuration metadata 里一条条数出来的。与空值直接相关的一条是 default-property-inclusion，绑定的类型是 com.fasterxml.jackson.annotation.JsonInclude$Include 枚举，yml 里写 non_null。serialization 与 deserialization 两条 Map 属性还在，键的类型已经换成 tools.jackson 的枚举，比如给 deserialization 配 FAIL_ON_UNKNOWN_PROPERTIES: true 就能打开未知字段报错。

## 默认行为：先看 Jackson 3 怎么处理 null

空值策略的第一步是搞清楚默认值，不摸清基准，后面的对比都没有参照。工程里我放了一个探针端点，注入 Boot 容器里的 ObjectMapper，回显三个 Feature 的开关状态：

```java
@GetMapping("/defaults")
public Map<String, Object> defaults() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mapperClass", mapper.getClass().getName());
    out.put("failOnUnknownProperties",
            mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    out.put("failOnNullForPrimitives",
            mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
    out.put("failOnEmptyBeans",
            mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));
    return out;
}
```

实测输出一行顶三段解释：

```json
{"mapperClass":"tools.jackson.databind.json.JsonMapper",
 "failOnUnknownProperties":false,
 "failOnNullForPrimitives":true,
 "failOnEmptyBeans":false}
```

三个值里有一个值得停下来看：FAIL_ON_NULL_FOR_PRIMITIVES 默认是开着的。JSON null 打进 int 字段会直接抛异常，金额字段出过事的项目对这个默认值会有体感，我第一次看到它默认开启时还挺意外。FAIL_ON_UNKNOWN_PROPERTIES 默认关闭，请求体里多出来的字段被静默忽略。FAIL_ON_EMPTY_BEANS 默认关闭，没有可输出属性的对象序列化成空对象，前端拿到 `{}` 的场景多半与它有关。

三个开关都能经 spring.jackson 属性调整。deserialization 与 serialization 是 Map 类型，键写 Feature 枚举名，比如把 primitive 的严格模式关掉：

```yaml
spring:
  jackson:
    deserialization:
      FAIL_ON_NULL_FOR_PRIMITIVES: false
```

键名与探针端点回显的枚举名一一对应，宽松绑定不改动枚举常量本身。这条属性与探针端点读的是同一组 Feature 开关，我后续的实测全部保持在默认值状态。

序列化方向的默认值靠一个不贴任何注解的 DTO 实测。订单 2 是一张游客单，customerPhone、couponCode、remark、discountCents 四个字段为 null：

```json
{"amountCents":12990,"couponCode":null,"customerName":"李四","customerPhone":null,
 "discountCents":null,"id":2,"orderNo":"SO-2026-0002","quantity":1,"remark":null,
 "status":"CREATED"}
```

null 字段原样输出，10 个字段一个不少，字段名按字母序排列。Jackson 3 的默认 inclusion 仍是 ALWAYS，想省掉空字段必须显式声明，框架不会替业务做这个决定，我觉得这个设计立场挺有意思：它宁可让响应冗余，也不悄悄丢字段。

## 三种空值策略同场对比

策略一，类级 @JsonInclude(NON_NULL)。注解贴在 DTO 类上，整类所有 null 字段都不输出：

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderNonNullResponse {
    private Long id;
    private String orderNo;
    private String customerName;
    // 其余字段同默认 DTO
}
```

订单 2 经过这个 DTO 输出，4 个 null 字段全部消失，10 个字段剩 6 个：

```json
{"amountCents":12990,"customerName":"李四","id":2,
 "orderNo":"SO-2026-0002","quantity":1,"status":"CREATED"}
```

策略二，字段级 @JsonInclude(NON_NULL)。注解只贴在 couponCode 一个字段上，其余字段维持默认行为：

```java
public class OrderFieldIncludeResponse {
    private Long id;
    private String orderNo;
    private String customerName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String couponCode;

    private String remark;
    // 其余字段同默认 DTO
}
```

实测输出里 couponCode 整个键消失了，remark 与 discountCents 仍是 null。字段级注解只作用于贴了它的字段，类里其他 null 字段不受影响。这个粒度适合「个别字段有契约要求、其余保持完整结构」的场景，比如对账方要求某个可空字段缺席时代表未启用，而不是 null。

策略三，全局属性。yml 里打开我留的那行注释，或者验证脚本里用命令行参数起服务：

```yaml
spring:
  jackson:
    default-property-inclusion: non_null
```

全局生效后，订单 2 的默认 DTO 输出与类级注解的效果一致，4 个 null 字段省略；record DTO 的输出也同步变化。三种策略同场对比如下：

| 策略 | 写法位置 | 作用域 | 订单 2 实测 |
|---|---|---|---|
| 默认行为 | 无 | 整个应用 | 10 字段，4 个 null 输出 |
| 字段级 NON_NULL | 字段上 | 贴注解的那个字段 | 仅 couponCode 消失 |
| 类级 NON_NULL | DTO 类上 | 当前 DTO | 4 个 null 全省，剩 6 字段 |
| 全局 non_null | spring.jackson 属性 | 整个应用 | 与类级一致，record 同步受控 |

作用域从小到大，选型的依据是契约的归属：字段契约写在字段上，接口契约写在 DTO 上，应用级口径才走全局属性。三者叠加时的求值顺序由 USE_DEFAULTS 串起来，字段注解最先表态，没表态再看类注解，然后落到全局属性，没有一层声明时才是默认的 ALWAYS。我把这条判定链画成了一张图：

![](https://static.xiongneng.me/jackson3-null-decision-flow-20260922055529.png)

## 自定义序列化器：金额分转元

金额在数据库里存分（long），对外接口输出元（两位小数），我把换算逻辑收在序列化器里，DTO 字段不出现重复换算代码：

```java
public class CentsToYuanSerializer extends ValueSerializer<Long> {

    @Override
    public void serialize(Long cents, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeNumber(OrderService.centsToYuan(cents));
    }
}
```

基类 ValueSerializer 在 tools.jackson.databind 包，第二个参数是 JsonGenerator，第三个是 SerializationContext。DTO 字段上用 @JsonSerialize 指定（注解在 tools.jackson.databind.annotation 包）：

```java
public class OrderAmountResponse {
    private Long id;
    private String orderNo;

    @JsonSerialize(using = CentsToYuanSerializer.class)
    private long amountYuan;

    @JsonSerialize(using = CentsToYuanSerializer.class)
    private Long discountYuan;
}
```

订单 1 的实测输出，24182 分变成 241.82 元，2000 分变成 20.00 元，尾零保留（BigDecimal 按 scale 2 构造）：

```json
{"amountYuan":241.82,"discountYuan":20.00,"id":1,"orderNo":"SO-2026-0001"}
```

订单 2 的 discountYuan 是 null，输出仍是 "discountYuan":null。null 值不进自定义序列化器，走框架的 NullSerializer，这点我是踩过才记住的：「字段可能为空的金额」场景要单独想清楚，要么接受 null 出现在响应里，要么给 DTO 补 NON_NULL，要么像下一节那样在反序列化方向处理空值。

反方向是请求体里的元转分。ValueDeserializer 的子类把 241.82 解析成 24182：

```java
public class YuanToCentsDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) {
        return OrderService.yuanToCents(p.getDecimalValue());
    }
}
```

取数用的是 JsonParser 的 getDecimalValue()。请求体里写 "amount": 241.82，实测解析落库 amountCents 为 24182。这两个自定义类与 Boot 侧的装配关系，我画成了一张类图：

![](https://static.xiongneng.me/jackson3-serializer-class-diagram-20260922055640.png)

## 反序列化容错

反序列化方向的空值由 @JsonSetter 控制，它管「JSON 里显式写了 null 时字段落什么值」：

```java
public class OrderCreateRequest {

    private String customerName;

    @JsonAlias("orderNo")
    private String merchantOrderNo;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private String couponCode;

    private int quantity;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Long discountCents;

    @JsonProperty("amount")
    @JsonDeserialize(using = YuanToCentsDeserializer.class)
    private Long amountCents;
}
```

Nulls 枚举共五个值：SET、SKIP、FAIL、AS_EMPTY、DEFAULT。AS_EMPTY 把 null 落成类型的空值，String 落空字符串、Long 落 0。我构造了一个请求体，带一个未知字段 extraField，加上 null 值与元金额一起发过去：

```text
请求：{"customerName":"王五","orderNo":"TM-2026-777","extraField":"x",
      "couponCode":null,"quantity":1,"discountCents":null,"amount":241.82}
响应：200
     {"customerName":"王五","merchantOrderNo":"TM-2026-777",
      "couponCode":"","quantity":1,"discountCents":0,"amountCents":24182}
```

四件事同一次请求里验证完：未知字段 extraField 被静默忽略（FAIL_ON_UNKNOWN_PROPERTIES 默认关闭）；@JsonAlias 让别名 orderNo 命中 merchantOrderNo；couponCode 的 null 落成空字符串；discountCents 的 null 落成 0；241.82 换算成 24182 分。

AS_EMPTY 的语义边界要清楚：它给的是「空值」，空字符串与 0 在对账口径里依然是有效值，与「字段缺席」不同。null 想被跳过保留字段原值用 SKIP，想直接报错用 FAIL，FAIL 会抛出携带字段名的 InvalidNullException。我把三个取值的实测差异钉在同一行字段上：

```text
JSON: "couponCode": null

Nulls.AS_EMPTY → couponCode 落 ""（空字符串，仍是有效值）
Nulls.SKIP     → setter 不被调用，字段保持 Java 侧的初始值
Nulls.FAIL     → 请求失败，InvalidNullException 携带PropertyName(couponCode)
```

AS_EMPTY 适合「把 null 规整成可计算的缺省值」的入口，比如优惠券字段 null 落空串后，下游代码不再需要判 null；FAIL 适合契约严格的内部接口，null 一出现立刻暴露调用方问题；SKIP 的用途最窄，字段有 Java 侧默认值时才用得上。

primitive 的行为我单独实测。quantity 是 int，请求体显式给 "quantity": null：

```text
HTTP 400 | content-type: application/problem+json
{"detail":"请求体解析失败: Cannot map `null` into type `int`
  (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)",
 "instance":"/api/orders/parse","status":400,"title":"Invalid request body"}
```

返回 400，没有静默落 0，错误文本里直接给出了关闭开关的名字，这是本次实测里我最欣赏的一个默认值：null 与 0 的区分在 primitive 字段上也被守住，代价是客户端必须保证不该为 null 的字段别发 null。捕获侧的代码同样有 Jackson 3 的痕迹：DatabindException 是 RuntimeException 的子类，@RestControllerAdvice 的处理方法不需要 throws 声明：

```java
@ExceptionHandler(DatabindException.class)
public ProblemDetail handleDatabind(DatabindException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "请求体解析失败: " + ex.getOriginalMessage());
    pd.setTitle("Invalid request body");
    return pd;
}
```

一个请求从进来到落地，中间要过未知字段、别名、空值语义、自定义换算、primitive 严格模式五道关口，出口只有 200 与 400 两种，我把整条容错链路画了出来：

![](https://static.xiongneng.me/jackson3-deserialize-tolerance-flow-20260922055532.png)

## 完整案例：一次运行的全部输出

验证脚本起两次服务。A 轮默认配置发 11 个请求，B 轮开全局 non_null 再发 2 个，输出我逐条实录。三种策略对同一张游客单（订单 2）的输出对比：

```text
默认行为        → couponCode/customerPhone/remark/discountCents 均带 null，10 字段
类级 NON_NULL   → 4 个 null 字段省略，6 字段
字段级 NON_NULL → 仅 couponCode 省略，其余 null 照常
全局 non_null   → 与类级注解效果一致，record 同步受控
```

record 的输出单独看一眼。组件按声明序排列，与普通 DTO 的字母序不同：

```json
{"id":2,"orderNo":"SO-2026-0002","customerName":"李四","customerPhone":null,
 "couponCode":null,"quantity":1,"amountCents":12990,"discountCents":null,
 "status":"CREATED","createdAt":"2026-09-20T11:05:00"}
```

LocalDateTime 组件零配置输出 ISO-8601 文本，Jackson 3 的 java.time 支持已内置在 databind 里，不再需要单独注册模块。全局 non_null 打开后，这张 record 输出里的 4 个 null 同样消失，customerPhone 与 couponCode 整行不见：

```json
{"id":2,"orderNo":"SO-2026-0002","customerName":"李四","quantity":1,
 "amountCents":12990,"status":"CREATED","createdAt":"2026-09-20T11:05:00"}
```

record 的空值策略与普通 DTO 共用同一套注解与全局属性，@JsonInclude 贴在 record 声明上、@JsonProperty 定名、@JsonSetter 控制组件的反序列化空值，写法完全一致。区别只剩两处：输出顺序是声明序，组件不可变所以没有 setter，反序列化走构造器参数，@JsonSetter 贴在组件上同样生效。

13 个场景的完整行为汇总成一张表：

| # | 请求 | 实测结果 |
|---|---|---|
| 1 | GET /api/jackson/defaults | JsonMapper + 三 Feature 默认值实证 |
| 2 | GET /api/orders/2（默认 DTO） | 10 字段，4 个 null 原样输出 |
| 3 | GET /api/orders/1（默认 DTO） | 全字段非空，输出与字段值一致 |
| 4 | GET /api/orders/2/non-null | 4 个 null 省略，剩 6 字段 |
| 5 | GET /api/orders/2/field-include | 仅 couponCode 消失 |
| 6 | GET /api/orders/2/record | 声明序输出，null 照常 |
| 7 | GET /api/orders/1/amount | 24182→241.82，2000→20.00 |
| 8 | GET /api/orders/2/amount | discountYuan 为 null，输出 null |
| 9 | POST /api/orders/parse（混合容错） | 未知字段忽略，AS_EMPTY 生效，241.82→24182 |
| 10 | POST /api/orders/parse（quantity:null） | 400，提示 FAIL_ON_NULL_FOR_PRIMITIVES |
| 11 | POST /api/orders（下单） | 201，amount 99.90 落库 9990 分 |
| 12 | B 轮 GET /api/orders/2 | 全局 non_null 生效，null 全省 |
| 13 | B 轮 GET /api/orders/2/record | record 同步受控，null 全省 |


下单入口把容错规则串成完整链路，请求体 "amount": 99.90 落库 9990 分，HTTP 201 返回建好的订单：

```text
HTTP 201 | content-type: application/json
{"amountCents":9990,"couponCode":"VIP30","customerName":"赵六","id":3,
 "orderNo":"SO-2026-0003","quantity":3,"status":"CREATED"}
```

响应 DTO 用的是类级 NON_NULL 的那个，没填的 customerPhone 不出现在响应里。对账脚本按「字段缺席即未启用」的口径解析，null 判定的分支整体消失。说实话，开头那两桩对账事故，在接口契约这一层就都能拦下来。

## 测试怎么写

11 个用例起真实 Tomcat 跑 HTTP 断言，客户端是 RestTestClient，RANDOM_PORT 下手工绑定地址：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JacksonNullStrategyTest {

    @LocalServerPort
    private int port;

    private RestTestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void classLevelNonNullOmitsAllNulls() {
        String body = rest.get().uri("/api/orders/2/non-null")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body)
                .doesNotContain(":null")
                .contains("\"customerName\":\"李四\"");
    }
}
```

断言直接对响应字符串做 contains 与 doesNotContain，JSON 结构变化（字段消失）比数值比较更贴空值策略的本意，这是我写完才体会到的。反序列化容错用一个用例覆盖四个点，断言每个容错点落进字段的值：

```java
@Test
void deserializationTolerances() {
    String payload = """
            {"customerName":"王五","orderNo":"TM-2026-777","extraField":"x",
             "couponCode":null,"quantity":1,"discountCents":null,"amount":241.82}""";
    String body = rest.post().uri("/api/orders/parse")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(payload)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
    assertThat(body)
            .contains("\"merchantOrderNo\":\"TM-2026-777\"")
            .contains("\"couponCode\":\"\"")
            .contains("\"discountCents\":0")
            .contains("\"amountCents\":24182");
}
```

payload 里的 extraField 我故意不删，它就是「未知字段被忽略」这条断言的前提，删掉后用例还能过，但容错覆盖少了一半。primitive 的 400 与元转分的 201 各占一个用例，九个用例合起来把探针、序列化、反序列化三个方向全部钉住。全局策略轮用测试属性单独起一个上下文，验证 default-property-inclusion 对普通 DTO 与 record 同时生效：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.default-property-inclusion=non_null")
class GlobalInclusionPropertyTest {
    // 两个用例：普通 DTO 与 record 的 null 字段同时消失
}
```

mvn test 一次跑完的对账：

```text
GlobalInclusionPropertyTest    2    3.933s    全局 non_null 对 DTO 与 record 生效
JacksonNullStrategyTest        9    0.621s    默认行为 / 三种策略 / 序列化器 / 容错
合计                          11    BUILD SUCCESS
```

应用日志 ERROR 0 行。全部数字来自同一次运行，原始记录我都留了底。

## 避坑指南

**坑一，FAIL_ON_NULL_FOR_PRIMITIVES 默认是开的，我第一次实测就撞上了。** JSON null 打进 int、long 字段直接 400，错误文本自带修复提示。想让 null 落 0，显式关掉这个 Feature，或者把字段类型换成包装类，业务代码里对包装类自己判空。

**坑二，自定义反序列化器取小数要用 getDecimalValue()。** getNumberValue() 对 241.82 这类小数返回的是 Double，直接强转 BigDecimal 会抛 ClassCastException，我就这么栽过一次。取 BigDecimal 走 getDecimalValue()，取整型再按需转换。

**坑三，null 不进自定义序列化器。** 字段值为 null 时 Jackson 走 NullSerializer，@JsonSerialize 指定的序列化器不会被调用。空字段想输出缺省值（比如金额输出 0.00），要么在序列化器里用 Nulls.AS_EMPTY 的空值语义，要么接受 null 出现并交给客户端判断。

**坑四，字段级 @JsonInclude 只管贴了注解的字段。** 想整类省略 null 用类级注解，只想动一个字段用字段级，两者混用时字段级优先。我排查时就以为贴一个字段全类生效，盯着其余 null 字段还在输出看了半天，方向从一开始就错了。

**坑五，默认 inclusion 是 ALWAYS，空字段省略必须显式声明。** Jackson 3 在这一点上取的是保守默认值，没配策略的接口照常输出 null 字段。对账、开放平台这类按字段解析的外部消费方，上线前先对一遍口径：null、缺字段、空字符串是三种不同的值。

**坑六，record 的输出顺序是声明序。** 普通 POJO 的字段按字母序输出，record 组件按声明序。契约方如果按字节序或字段位置对账，两种 DTO 的输出顺序不同，换 DTO 形态前先确认对方的解析方式。

**坑七，未知字段默认静默忽略。** FAIL_ON_UNKNOWN_PROPERTIES 在 Jackson 3 默认 false，请求体多打一个字段名不会有任何提示。契约收敛严格的接口要显式打开这个 Feature，让多出来的字段在测试期就报错。

**坑八，定制 mapper 的入口是 JsonMapperBuilderCustomizer。** Boot 侧想调 Feature、注册 Module，实现 org.springframework.boot.jackson.autoconfigure 包下的 JsonMapperBuilderCustomizer 接口，方法签名 customize(JsonMapper.Builder)。注解风格的序列化器注册用 @JacksonComponent（org.springframework.boot.jackson 包）。

## 小结

空值策略怎么选，看契约的归属：单个字段有约定就贴字段级注解，整个 DTO 有统一口径就贴类级注解，应用级要对齐一种口径才动 spring.jackson 的全局属性，三者叠加时字段级最先表态。反序列化入口的 null 优先考虑 AS_EMPTY，把 null 规整成下游可计算的缺省值；契约严格的内部接口用 FAIL，让调用方的问题当场暴露。金额这类有换算口径的字段，把换算收进自定义序列化器，DTO 里别出现重复代码。

没解决的事也直说：null 打进对象类型字段时走的是框架的 NullSerializer，自定义 NullSerializer 能接管到什么程度、「null 字段输出占位符」这类需求怎么落，我这次没测，等遇到真实场景再补一轮。

## 参考链接

- [Spring Boot JSON 特性文档](https://docs.spring.io/spring-boot/reference/features/json.html)：spring.jackson 属性与 Jackson 3 自动配置说明
- [Jackson 3.0 Wiki](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0)：包名、JsonMapper 与默认值变化的权威说明
- [tools.jackson javadoc](https://javadoc.io/doc/tools.jackson.core/jackson-databind)：ValueSerializer / ValueDeserializer / Nulls 的 API 参考
- [spring-boot-jackson 4.1.1（Maven Central）](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-jackson/4.1.1/)：本文属性清单的制品实证来源
