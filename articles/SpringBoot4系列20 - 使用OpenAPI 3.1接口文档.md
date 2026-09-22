---
title: SpringBoot4系列20 - 使用OpenAPI 3.1接口文档
slug: sb4-openapi
date: 2026-09-20 23:30:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, OpenAPI, Swagger, springdoc ]
draft: false
---

一家公司的订单服务有三种消费方：小程序前端、运营后台、一家做对账的合作方。接口怎么调、字段什么含义，靠群里发消息同步，消息刷走一次，联调就对不上一次。我把字段说明放进共享文档试过一版，代码改了没人记得改文档，三个月之后那份文档没有一行敢信。前端问我「金额是元还是分」，答案明明就在代码里，却不在任何一份我能拿得出手的文档里。

后来我把接口描述写进代码、让工具从代码里生成文档，这事才算理顺。OpenAPI 定义了这份描述的格式，springdoc-openapi 负责在 Spring Boot 里完成生成：启动时扫描注解，产出一份 JSON 描述文件，swagger-ui 把这份 JSON 渲染成可交互的文档页面。文档与代码在同一个提交里，字段改了文档必然跟着变，这就是我要的效果。

![](https://static.xiongneng.me/openapi-doc-dataflow-20260922045018.png)

这篇我在 Spring Boot 4.1.1 上把系列一直在用的订单接口文档化：springdoc-openapi 3.1.1 扫描 @Tag、@Operation、@Schema 注解生成 OpenAPI 3.1 文档，GroupedOpenApi 把 /api/orders 与 /api/admin 分成两组，swagger-ui 5.32.14 提供带凭据的调试页面，管理接口的 HTTP Basic 认证声明进文档。18 个测试全绿，全部数字来自同一次运行，我留了底。

## 文档从哪来：集成方案与依赖事实

写代码前我习惯先把依赖的来路确认清楚，下面这些全部来自 Central 制品解包与真实启动实测。

先说与本系列其他篇最大的不同：Boot 官方 BOM 不管理 springdoc。我翻了 spring-boot-dependencies 4.1.1，里面没有任何 org.springdoc 的版本条目，starter 的版本必须显式写进 pom，省掉版本号 Maven 第一步就报错。九成是因为大家习惯了系列里 nimbus-jose-jwt 那种「BOM 管版本、工程不写号」的写法，在这篇不成立。

版本我选 3.1.1，依据有三，全部制品实证：springdoc-openapi 3.1.1 的父 POM 里，parent 就是 spring-boot-starter-parent 4.1.0，与本篇基线同代；最小工程在 Boot 4.1.1 上真实启动，GET /v3/api-docs 返回 200，文档的 openapi 字段是 "3.1.0"；Central 上 springdoc 3.x 线的最新稳定版就是它，2026-09-06 发布。

starter 的组成我解包看过，三层套娃加一个页面 webjar：

```text
springdoc-openapi-starter-webmvc-ui 3.1.1
  = springdoc-openapi-starter-webmvc-api
    + springdoc-openapi-starter-common
    + swagger-ui 5.32.14（webjar，页面静态资源随 jar 分发）
springdoc-openapi-starter-common
  = spring-boot-starter + spring-boot-autoconfigure
    + spring-boot-validation + spring-boot-jackson
    + io.swagger.core.v3:swagger-core-jakarta 2.2.55
```

swagger-core-jakarta 是注解与对象模型的实现方，@Schema 这类注解的定义和「注解转 schema」的逻辑都在它里面，版本 2.2.55 由 springdoc 的父 POM 管理，工程不用自己操心。

![](https://static.xiongneng.me/openapi-component-map-20260922045018.png)

自动配置分布在三个模块里（unzip AutoConfiguration.imports 实证），包名都在 org.springdoc 下：

```text
org.springdoc.core.configuration.SpringDocConfiguration                     （common）
org.springdoc.core.properties.SpringDocConfigProperties                     （common，属性 bean）
org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration        （webmvc-api）
org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration（webmvc-api，分组在这里）
org.springdoc.webmvc.ui.SwaggerConfig                                       （webmvc-ui，页面端点）
```

GroupedOpenApi 类在 org.springdoc.core.models 包，io.swagger.v3.oas.models 包里没有它，import 选错一边就是编译错误。IDE 自动补全两个包都提示得出，眼要睁着。

springdoc.* 前缀的属性共 106 条（spring-configuration-metadata 实证），本篇用到四条，默认值如下：

```text
springdoc.api-docs.path       默认 /v3/api-docs
springdoc.api-docs.enabled    默认 true
springdoc.swagger-ui.path     默认 /swagger-ui.html
springdoc.api-docs.version    默认 openapi-3-1，输出即 3.1.x
```

四条默认值都能满足开头的需求，yml 里一行 springdoc 配置不写，文档照样出来。后面「属性开关」一节我把其中两条的行为实测了一遍。

## 依赖和配置

工程四个 starter：webmvc 撑接口面，validation 让校验注解生效并顺手进 schema，security 管住 /api/admin，springdoc 那个是主角：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<!-- 本篇主角。Boot BOM 不管理 springdoc，版本必须显式写 -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

测试侧照系列惯例引两个：webmvc-test 提供 @AutoConfigureMockMvc 本体，security-test 提供 httpBasic() 请求后处理器，缺一个就是注解找不到或者 401 测不了，我试过漏引的滋味。

application.yml 三段，端口 18200 延续系列约定：

```yaml
spring:
  application:
    name: springboot4-openapi
  security:
    user:
      name: admin
      password: admin-2026
      roles: ADMIN

server:
  port: ${SERVER_PORT:18200}

springdoc:
  swagger-ui:
    display-request-duration: true
    tags-sorter: alpha
    operations-sorter: alpha
```

Basic 账号来自 spring.security.user.*，roles 配成 ADMIN 对应过滤器链的 hasRole("ADMIN")。springdoc 那三行只影响展示：调试时显示每次请求的耗时，标签与操作按字母序排，不写也不影响文档内容。

## 核心代码

### 注解：把接口说明贴在代码旁

Controller 上四个注解各管一段，订单列表接口一个方法就齐了：

```java
@RestController
@RequestMapping("/api/orders")
@Tag(name = "订单", description = "订单查询与创建，面向前端与第三方联调")
public class OrderController {

    @Operation(summary = "订单列表", description = "按状态过滤，不传 status 返回全部")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功",
                    useReturnTypeSchema = true)})
    @GetMapping
    public List<OrderResponse> list(
            @Parameter(description = "按订单状态过滤", in = ParameterIn.QUERY,
                    example = "NEW")
            @RequestParam(required = false) OrderStatus status) {
        return orders.list(status).stream().map(OrderResponse::from).toList();
    }
```

@Tag 给文档分栏，@Operation 说明一个操作，@Parameter 描述查询参数，@ApiResponse 声明响应。useReturnTypeSchema 让 200 的响应结构直接用返回值的 schema，不用再手写一份。实测最终文档收了 4 个路径条目、5 个操作，tags 两个：订单与订单运维。

错误响应的声明走复用路线，这笔账我觉得稳赚：401、403、404、409 各写一次，存进 components，控制器用 ref 引用：

```java
@Bean
OpenAPI orderOpenApi() {
    return new OpenAPI()
            .info(new Info().title("订单服务 API").version("v1.0.0"))
            .components(new Components()
                    .addResponses("Duplicate", shared(409, "单号重复",
                            "urn:problem-type:duplicate-order", "订单号已存在")));
}
```

```java
@ApiResponse(responseCode = "409", description = "单号重复",
        ref = "#/components/responses/Duplicate")
```

四类错误响应各声明一次之后，OrderController 与 AdminController 的 @ApiResponses 里只剩 ref，改文案动一处就够，两边永远说一样的话。

### DTO 到 schema 的映射

![](https://static.xiongneng.me/openapi-schema-mapping-20260922045018.png)

入参 DTO 上的注解决定对接方怎么理解字段，@Schema 负责描述，校验注解负责约束，两类都会出现在文档里：

```java
public record CreateOrderRequest(

        @Schema(description = "业务单号，全局唯一", example = "SO-2026-0001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        String orderNo,

        @Schema(description = "订单金额，单位元", example = "359.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal amount,

        @Schema(description = "备注，可空", example = "加急发顺丰", maxLength = 200)
        @Size(max = 200)
        String note) {
}
```

实测生成的 schema 逐项对得上：

```json
"CreateOrderRequest": {
  "properties": {
    "orderNo": {"type":"string", "description":"业务单号，全局唯一",
                "example":"SO-2026-0001", "maxLength":32, "minLength":0},
    "amount": {"type":"number", "description":"订单金额，单位元",
               "example":359.0, "minimum":0.01}},
  "required": ["amount", "orderNo"] }
```

@NotBlank 与 @NotNull 把两个字段送进 required 数组；@Size(max = 32) 变成 maxLength:32，附带一个 minLength:0，挺有意思，这个副作用来自 @Size 的 min 缺省值，不是我写错了；@DecimalMin("0.01") 变成 minimum:0.01。@Digits 没有对应的 schema 关键字，两位小数的规则要靠 description 或 example 补充说明。

枚举没有独立 schema，OrderResponse 的 status 字段内联四个状态名：

```json
"status": {"type":"string", "description":"订单状态",
           "enum":["NEW","PAID","SHIPPED","CANCELLED"]}
```

字段名保持 record 的驼峰原样进 schema，Instant 序列化成 type:string 加 format:date-time。前端再问「金额什么格式、状态有哪几种」，我把这份 JSON 甩过去就行，它与代码同一次提交，不用我另外维护。

### 分组：两份文档各管各的

一个服务同时伺候前端与运营后台时，全量文档里互相找。GroupedOpenApi 一个 bean 一份分组，pathsToMatch 圈定范围：

```java
@Bean
GroupedOpenApi ordersApi() {
    return GroupedOpenApi.builder()
            .group("orders")
            .displayName("订单接口")
            .pathsToMatch("/api/orders/**")
            .build();
}

@Bean
GroupedOpenApi adminApi() {
    return GroupedOpenApi.builder()
            .group("admin")
            .displayName("订单运维接口")
            .pathsToMatch("/api/admin/**")
            .build();
}
```

实测两组文档各自 200：/v3/api-docs/orders 收 2 个路径条目（90.5ms），/v3/api-docs/admin 收另外 2 个（49.6ms），全量文档 /v3/api-docs 是两组并集（34.8ms）。swagger-ui 页面右上角的下拉框读这份配置：

```json
{"configUrl":"/v3/api-docs/swagger-config",
 "urls":[{"url":"/v3/api-docs/orders","name":"订单接口"},
         {"url":"/v3/api-docs/admin","name":"订单运维接口"}]}
```

name 字段取的是 displayName，没配 displayName 时才落回 group 名。group 名负责拼文档 URL，displayName 负责给人看，两个名字各有各的岗位，别混着用。

### 与 Security 的衔接

管理接口的认证怎么进文档，先讲取舍。18 与 19 篇的过滤器链是 JWT，OpenAPI 侧声明 Bearer securityScheme 之后，调试前还得先取一枚令牌；本篇管理面只有两个接口，HTTP Basic 让声明、调试、实现三件事同时最短，所以我选 basic。真要接 JWT，把 SecurityScheme 的 type 换成 http、scheme 换成 bearer，再补 bearerFormat: JWT，声明路线完全一样。

安全方案声明进 components：

```java
@Bean
OpenAPI orderOpenApi() {
    return new OpenAPI()
            .components(new Components()
                    .addSecuritySchemes("basicAuth", new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                            .description("HTTP Basic，管理侧账号见 spring.security.user.*")));
}
```

管理侧控制器整体挂上引用，两个操作的 401 与 403 用复用组件声明：

```java
@Tag(name = "订单运维", description = "管理侧接口，HTTP Basic 认证，运营后台调用")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "未认证",
                    ref = "#/components/responses/Unauthorized"),
            @ApiResponse(responseCode = "403", description = "权限不足",
                    ref = "#/components/responses/Forbidden"),
            @ApiResponse(responseCode = "404", description = "订单不存在",
                    ref = "#/components/responses/NotFound")})
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Void> delete(@PathVariable Long orderId) {
        if (!orders.delete(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return ResponseEntity.noContent().build();
    }
}
```

@SecurityRequirement 贴在类上，一次覆盖全部管理操作，文档里每个操作都带 basicAuth 标记。实测文档里 components.securitySchemes.basicAuth 是 {"type":"http","scheme":"basic"}，swagger-ui 的 Authorize 按钮因此有了用武之地：填一次账号密码，页面上调管理接口就自动带 Basic 头。实测带凭据调 GET /api/admin/orders 返回 200（145.4ms），不带凭据返回 401（8.9ms）。

过滤器链这一侧，文档端点放行、管理接口收权：

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/docs", "/docs/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
        .httpBasic(basic -> basic.realmName("order-service"));
    return http.build();
}
```

CSRF 关掉之后，swagger-ui 页面里的 Try it out 才能直接发 POST 与 DELETE，不然每个写操作都会撞上 403。

### 属性开关：改名与下线

springdoc.* 的行为我用两次重启实测。第一次改路径，加启动参数 --springdoc.api-docs.path=/internal/api-docs：

```text
GET /internal/api-docs         -> 200
GET /internal/api-docs/orders  -> 200（分组文档跟着搬）
GET /v3/api-docs               -> 404
GET /v3/api-docs/orders        -> 404
```

第二次关开关，加 --springdoc.api-docs.enabled=false：

```text
GET /v3/api-docs               -> 404
GET /v3/api-docs/admin         -> 404
GET /swagger-ui/index.html     -> 404
```

第二次的结果有点出乎我意料：文档与页面共用这一个总开关，SwaggerConfig 的端点注册条件跟着 api-docs 开关走，想「藏文档但留页面」的组合不存在。内网对内开放、外网网关封禁的组合下，应用侧留这道开关做最后一道闸，够用。

## 完整案例

一轮实测的场景与结果，验证脚本三启对账（默认配置、开关关闭、路径改名）。先看文档本体，GET /v3/api-docs 返回 200（34.8ms），头部实录：

```json
{"openapi":"3.1.0",
 "info":{"title":"订单服务 API","description":"SpringBoot4 系列示例：前端与第三方联调的订单接口文档","version":"v1.0.0"},
 "servers":[{"url":"http://localhost:18200","description":"Generated server url"}],
 "tags":[{"name":"订单运维","description":"管理侧接口，HTTP Basic 认证，运营后台调用"},
         {"name":"订单","description":"订单查询与创建，面向前端与第三方联调"}]}
```

头部之后是 4 个路径条目、5 个操作，components.schemas 里两个模型：CreateOrderRequest 与 OrderResponse。对接方拿这一份 JSON 就能生成各语言的客户端代码，OpenAPI 生态里这类工具是现成的。

![](https://static.xiongneng.me/openapi-swagger-ui-page-20260920123953.png)

浏览器打开 /swagger-ui.html 会 302 到 /swagger-ui/index.html（实测页面 200，7.6ms），渲染出来就是上图：右上角下拉选分组，OAS 3.1 徽标标明文档版本，Authorize 按钮存 Basic 凭据，每个操作展开后有参数表格和 Try it out。

接口行为一轮跑完：

```text
POST /api/orders                     合法请求体        -> 201  109.9ms  Location: /api/orders/1001
POST /api/orders                     同单号再来一次     -> 409  7.3ms   urn:problem-type:duplicate-order
POST /api/orders                     orderNo 空 金额负 -> 400  69.2ms  errors 数组带字段明细
DELETE /api/admin/orders/1002        无凭据            -> 401  8.9ms
GET  /api/admin/orders               Basic admin:*    -> 200  145.4ms
```

201 响应体与 400 的 errors 数组实录：

```json
{"id":1001,"orderNo":"SK-OA4-1","amount":359.0,"status":"NEW","note":"加急发顺丰","createdAt":"2026-09-20T04:37:10.624591500Z","createdBy":"system"}
```

```json
{"type":"urn:problem-type:bad-request","title":"请求参数校验失败","status":400,
 "detail":"请求参数校验失败",
 "errors":[{"field":"orderNo","message":"不能为空"},
           {"field":"amount","message":"必须大于或等于0.01"}]}
```

![](https://static.xiongneng.me/openapi-result-panels-20260920123951.png)

有一处细节我单独说：请求体里写的是 359.00，响应体与文档 example 里都是 359.0。JSON number 没有尾零的概念，这是序列化层的既有行为，跟精度没关系。对接双方按 number 理解金额即可，精度要求写进 description，别指望 example 替你传达。401 那一格的响应体是 Boot 默认 error JSON，与文档声明的 problem+json 对不上，原因见避坑指南坑六。

日志对账：整轮运行应用日志 ERROR 0 行。上面三张面板图来自同一次截图轮，耗时数字来自本轮验证轮，两轮的响应体结构一致，口径分开列在这里。

## 测试怎么写

六个测试类，18 个用例全绿：

```text
ApiDocsContractTest    6  文档契约：openapi 3.1.0、路径清单、schema 约束、枚举内联、复用响应、安全方案
GroupedApiDocsTest     3  分组：两组互不越界、swagger-config 的 urls 与 displayName
SwaggerUiPageTest      2  页面：/swagger-ui.html 302、/swagger-ui/index.html 200
ApiDocsToggleTest      1  开关：enabled=false 后 /v3/api-docs 404
ApiDocsPathRenameTest  1  改名：新路径 200、默认路径 404
OrderApiSmokeTest      5  行为：201、409、400、401、204 与 404
```

文档契约测试把断言打在 JSON 本体上：

```java
@Test
void dto_constraints_land_in_schema() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath(
                    "$.components.schemas.CreateOrderRequest.properties.orderNo.maxLength")
                    .value(32))
            .andExpect(jsonPath(
                    "$.components.schemas.CreateOrderRequest.properties.amount.minimum")
                    .value(0.01))
            .andExpect(jsonPath(
                    "$.components.schemas.CreateOrderRequest.required[0]").exists());
}
```

这类测试防的是「注解贴了、文档没生效」：依赖被谁注释掉了、@Size 写错成 @Length，页面照开但约束悄悄消失，一条 jsonPath 就能让它现形。openapi 字段同样盯住，断言 value("3.1.0")，springdoc 升级改了输出版本，测试第一时间知道，这就是契约的铁证。

开关与改名的测试用 properties 参数覆盖配置，一个测试类一个场景：

```java
@SpringBootTest(properties = "springdoc.api-docs.enabled=false")
@AutoConfigureMockMvc
class ApiDocsToggleTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void api_docs_endpoint_gone_when_disabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }
}
```

行为冒烟里的管理接口用 httpBasic() 后处理器走完整过滤器链，与 swagger-ui 调试时发的请求同构：

```java
mockMvc.perform(delete("/api/admin/orders/" + id)
                .with(httpBasic("admin", "admin-2026")))
        .andExpect(status().isNoContent());

mockMvc.perform(delete("/api/admin/orders/" + id))
        .andExpect(status().isUnauthorized());
```

两条断言并排放着，凭据的有无对应 204 与 401，与文档里声明的两个响应码一一对应。

## 避坑指南

**坑一，依赖不写版本号直接解析失败。** Boot 的 BOM 不管理 springdoc，pom 里省掉 version 标签，Maven 第一步就报依赖缺版本。版本要自己跟着 springdoc 的发布走，3.x 线对 Boot 4，与本系列基线搭配的是 3.1.1。

**坑二，api-docs.enabled=false 连 swagger-ui 页面一起下线。** SwaggerConfig 的端点注册条件跟着 api-docs 开关走，实测关掉之后 /swagger-ui/index.html 也是 404。「藏文档但留页面」这个组合不存在，要么全开要么全关。

**坑三，@Size(max = 32) 会带出 minLength:0。** @Size 的 min 缺省是 0，springdoc 如实写进 schema。不想让对接方看到 minLength:0，换用只产生 maxLength 的声明方式，或者接受这个无害的零。

**坑四，枚举默认不生成独立 schema。** components.schemas 里没有 OrderStatus，四个状态名内联在每个引用属性上。文档消费者想拿到可复用的枚举模型，要么接受内联（生成的客户端代码语义相同），要么显式改造声明方式并实测产物。

**坑五，swagger-config 的 name 不是 group 名。** urls[].name 取 GroupedOpenApi.displayName，测试断言写 group 名（"orders"）会挂。我就为这个闹过一次笑话，断言改回 displayName 才过。group 名拼文档 URL，displayName 给下拉框，两个名字各有用途。

**坑六，Security 的 401 响应体不是 problem+json。** Basic 认证失败发生在过滤器链里，@RestControllerAdvice 与 @ExceptionHandler 都够不着，响应体是 Boot 默认 error JSON（timestamp、status、error、path 四个键）。文档里声明的 401 组件描述的是业务语义；要统一响应体，得自定义 AuthenticationEntryPoint，18 与 19 篇就是这么做的。

**坑七，GroupedOpenApi 的包名容易写错。** 正确的是 org.springdoc.core.models.GroupedOpenApi，io.swagger.v3.oas.models 包里没有这个类，IDE 自动补全选错一边就是编译失败，我测试就栽在这里。

**坑八，example 的 359.00 会变成 359.0。** swagger-core 按 JSON number 存 example，尾零在序列化时丢失。对金额敏感的接口，用 description 写明「单位元，两位小数」，精度约定不靠 example 传达。

**坑九，分组文档跟着 api-docs.path 改名。** 改了 springdoc.api-docs.path 之后，分组文档挂在新前缀下（实测 /internal/api-docs/orders 返回 200），swagger-config 里的 urls 自动更新，但写死在网关或 nginx 配置里的旧路径不会自己改。

**坑十，管理操作别忘 @SecurityRequirement。** 只声明 securitySchemes 而不在操作上引用，swagger-ui 调试时不会带凭据头，每次 Try it out 都落 401。类级 @SecurityRequirement(name = "basicAuth") 一次覆盖整个 AdminController。

## 小结

选型建议：要文档跟着代码走，springdoc-openapi 是当下最省事的一条路，一个 starter 加几个注解就能开工，开销小到可以忽略。消费方多就上 GroupedOpenApi 分组，一个 bean 一份文档；管理接口认证方式简单就直接 Basic，声明、调试、实现三件事一起最短。文档契约值得写进测试，一条 jsonPath 断言就能拦住「注解贴了、文档没生效」这类静默退化。

没解决的事有两件：401 的响应体还是 Boot 默认 error JSON，与文档声明的 problem+json 不一致，统一它要自定义 AuthenticationEntryPoint，本篇没做；枚举想生成可复用的独立 schema，springdoc 默认给不了，改造方案我还没验证，先记在这里。

## 参考链接

- [springdoc-openapi 官方文档](https://springdoc.org/)：starter 坐标、属性说明与分组用法的权威来源
- [OpenAPI Specification 3.1.0](https://spec.openapis.org/oas/v3.1.0)：文档格式的规范本体，本文 schema 关键字的依据
- [swagger-core Annotations](https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations)：@Schema、@Operation 等注解的逐项说明
- [GroupedOpenApi 源码（springdoc-openapi）](https://github.com/springdoc/springdoc-openapi)：分组与 SwaggerConfig 的实现
- [Spring Boot Reference - Security](https://docs.spring.io/spring-boot/reference/web/security.html)：spring.security.user.* 属性与过滤器链
- [RFC 9457 - Problem Details](https://www.rfc-editor.org/rfc/rfc9457)：problem+json 响应结构
- [springdoc-openapi-starter-webmvc-ui 3.1.1 (Maven Central)](https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/3.1.1/)：本文版本结论的制品实证来源
