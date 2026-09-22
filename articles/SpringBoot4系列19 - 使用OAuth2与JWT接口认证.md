---
title: SpringBoot4系列19 - 使用OAuth2与JWT接口认证
slug: sb4-oauth2
date: 2026-09-20 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, OAuth2, JWT, Spring Security ]
draft: false
---

一家公司里，订单服务背后站着三个内部系统：物流系统查订单安排发货，报表系统拉数据做统计，对账系统定时核对金额。最早的接法是三方约定一个共享密码，每个系统调接口时都带上它。我接手这种系统时最怕的就是换密码：三个系统的配置、两份脚本、一个定时任务挨个改，改漏一个就出一批 401 告警。共享密码的要害在于「一台机器一个身份、一种权限」没有任何地方可以表达，全部调用方共用同一份凭据。OAuth2 的 client_credentials 授权模式解决的正是这件事：每个系统一个 client，一个 client 一组 scope，凭据过期自动换，接口侧只认 JWT 令牌本身。

![](https://static.xiongneng.me/oauth2-m2m-sequence-20260922043716.png)

这篇是我用 Spring Boot 4.1.1 把订单服务改造成 OAuth2 资源服务器的全程记录：RSA 密钥本地生成，JwtDecoder 校验 Bearer 令牌，scope claim 映射成 authority 后交给 `@PreAuthorize` 收敛，JwtEncoder 自签发 JWT 供演示与测试，oauth2-client 侧用 RestClient 挂 AuthorizedClientManager 走完整的 client_credentials 链路。12 个测试全绿，正文里的数字全部来自同一轮实测，我留了底。

## 编程模型与自动配置的事实

写代码之前我习惯先把坐标、自动配置与属性翻清楚，以下全部来自 Central 制品解包与 javap 实测，没有一条是我背出来的。

本篇资源服务器侧的 starter 坐标是 `spring-boot-starter-security-oauth2-resource-server`，组成（POM 实测）：

```text
spring-boot-starter-security-oauth2-resource-server
  = spring-boot-starter + spring-boot-starter-security
    + spring-boot-security-oauth2-resource-server
spring-boot-security-oauth2-resource-server（自动配置模块，Boot 4 独立拆分）
spring-security-oauth2-jose 7.1.1 = spring-security-core
    + com.nimbusds:nimbus-jose-jwt 10.9.1
```

客户端侧的 `spring-boot-starter-security-oauth2-client` 组成多一个 `spring-security-oauth2-client`，其余相同。JWT 的编解码能力全部经 jose 模块进来，nimbus-jose-jwt 由 BOM 管版本，工程里不用写版本号，升级时能省一半心。

Boot 4 模块化拆分后，资源服务器自动配置在 `spring-boot-security-oauth2-resource-server` 模块，包名 `org.springframework.boot.security.oauth2.server.resource.autoconfigure`，AutoConfiguration.imports 共 4 条，Servlet 工程直接相关的是前两条：

```text
org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration
org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration
org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration
```

![](https://static.xiongneng.me/oauth2-starter-map-20260922043716.png)

JwtDecoder 的装配路线用 javap 解出来有三条，`JwtDecoderConfiguration` 按属性决定造哪一种：

```text
jwtDecoderByPublicKeyValue()   属性 public-key-location   PEM 公钥文件
jwtDecoderByJwkKeySetUri()     属性 jwk-set-uri           远端 JWKS 端点
jwtDecoderByIssuerUri()        属性 issuer-uri            惰性（SupplierJwtDecoder 包装，首次请求才拉配置）
```

三条路线互斥，配了哪个属性走哪条。配 `audiences` 后自动挂一个 `JwtClaimValidator` 做受众校验，令牌的 aud claim 对不上直接 401。`spring.security.oauth2.resourceserver.jwt.*` 前缀下的属性共 10 条（spring-configuration-metadata 实证）：

```text
jwk-set-uri                  jwk-set-uri 路线的 JWKS 地址
issuer-uri                   issuer-uri 路线的签发方元数据地址
public-key-location          PEM 公钥，类型是 org.springframework.core.io.Resource
audiences                    List，aud 校验的合法值
jws-algorithms               默认 [RS256]
authority-prefix             权限前缀，默认 SCOPE_
authorities-claim-name       权限来源 claim，默认 scope
authorities-claim-delimiter  claim 为字符串时的分隔符
authorities-claim-expressions List，SpEL 表达式路线
principal-claim-name         主名来源 claim，默认 sub
```

JWT 核心类的位置我在 jose jar 里逐个确认过：`JwtDecoder`、`JwtEncoder`、`JwtEncoderParameters`、`JwsHeader` 都在 `org.springframework.security.oauth2.jwt` 包。`NimbusJwtEncoder.withKeyPair(pub, priv)` 返回 RSA 编码器构造器；`JwsHeader.with(algorithm)` 是 Security 7 自有的头抽象，`keyId()`、`type()` 链式设置。scope 到权限的映射走默认 converter：scope claim 的每个值变成一条 `SCOPE_` 前缀的 authority，`hasAuthority('SCOPE_orders:read')` 直接可比。

测试侧两个工具（spring-security-test 7.1.1 unzip 实证）：`SecurityMockMvcRequestPostProcessors.jwt()` 在 `org.springframework.security.test.web.servlet.request` 包，构造 MockMvc 用的 JwtAuthenticationToken；reactive 的对应物 `SecurityMockServerConfigurers.jwt()` 在 `org.springframework.security.test.web.reactive.server` 包。

## 依赖和配置

工程我摆了三块依赖：starter-webmvc 撑 HTTP 接口面，starter-security-oauth2-resource-server 是校验侧主角，starter-security-oauth2-client 供机器对机器调用演示：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
</dependency>
```

测试侧两个 starter：security-test 提供 jwt() 请求后处理器，webmvc-test 提供 `@AutoConfigureMockMvc` 本体，缺一不可，只引一个就是编译错误：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

RSA 密钥对我放在本地生成。一个工具类用 nimbus 的 RSAKeyGenerator 生成 2048 位密钥，落两个文件：全量 JWK（含私钥参数）给编码器，公钥 PEM 给资源服务器校验：

```java
public static void main(String[] args) throws Exception {
    Path outDir = Path.of(args.length > 0 ? args[0] : "src/main/resources/jwk");
    RSAKey jwk = new RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .keyID("order-" + Long.toHexString(System.currentTimeMillis()))
            .algorithm(JWSAlgorithm.RS256)
            .generate();

    Files.createDirectories(outDir);
    Files.writeString(outDir.resolve("rs256.json"), jwk.toJSONString());
    Files.writeString(outDir.resolve("public.pem"), toPem(jwk.toRSAPublicKey().getEncoded()));
    System.out.println("RSA 密钥对已生成 kid=" + jwk.getKeyID() + " -> " + outDir.toAbsolutePath());
}
```

生成一次提交进仓库即可，`rs256.json` 供运行期加载，`public.pem` 是 X.509 SubjectPublicKeyInfo 格式的 PEM 文本。要换密钥就重跑一次 main，新令牌带新 kid，旧令牌在校验侧立刻失效，我把它当作轮换密钥的最小做法。

application.yml 是全篇配置密度最高的一段，资源服务器两行，客户端注册一组：

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: classpath:jwk/public.pem
          audiences: order-service
      client:
        provider:
          order-service:
            token-uri: http://localhost:${SERVER_PORT:18190}/oauth2/token
        registration:
          logistics:
            provider: order-service
            client-id: logistics
            client-secret: logistics-secret-2026
            authorization-grant-type: client_credentials
            scope: orders:read
          reports:
            provider: order-service
            client-id: reports
            client-secret: reports-secret-2026
            authorization-grant-type: client_credentials
            scope: orders:read,orders:write
```

资源服务器侧我只配了 `public-key-location` 与 `audiences` 两项，Boot 的 JwtDecoderConfiguration 据此走 PublicKey 路线装配解码器并挂上受众校验，工程里不需要声明 JwtDecoder bean。客户端侧声明了 provider 与两个 registration：logistics 只有 orders:read，reports 有 read 加 write，正好对应「查单」与「改单」两种机器身份。

## 核心代码

### 过滤器链：oauth2ResourceServer 一处声明

![](https://static.xiongneng.me/oauth2-jwk-dataflow-20260922043716.png)

一个 SecurityConfig 类装下会话策略、路径授权与 Bearer 认证出口：

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, BearerProblemWriter problems) throws Exception {
        http
            // 纯 API + STATELESS 会话：没有浏览器表单、没有 Cookie 会话，
            // CSRF 保护没有作用对象
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // 演示令牌端点自带 client_id/client_secret 校验，不放进 Bearer 体系
                    .requestMatchers("/oauth2/token").permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden));
        return http.build();
    }
}
```

`.jwt(Customizer.withDefaults())` 意思是用容器里自动装配的 JwtDecoder 与 converter，什么都不覆盖。令牌解析失败（过期、签名不对、aud 不符）落 401 出口，认证通过但 scope 不够落 403 出口，两个出口都指向 BearerProblemWriter 的 problem+json。401 响应头按 RFC 6750 带 `WWW-Authenticate: Bearer`，令牌校验失败时追加 `error="invalid_token"`，调用方靠这个头就能区分「没带令牌」与「带了但无效」。

路径级规则我只写了两条：机器调用方没有角色概念，权限全部收敛到方法级注解上做，下一节展开。

### 编码器：JwtEncoder 从 JWK 文件造出

校验侧用自动装配，签发侧得自己声明。classpath 的 JWK 文件加载成一个 Provider，编码器从密钥对构造：

```java
@Component
public class SigningKeyProvider {

    private final RSAKey jwk;

    public SigningKeyProvider() {
        try {
            String json = new ClassPathResource("jwk/rs256.json")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.jwk = RSAKey.parse(json);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 jwk/rs256.json 失败，先跑 SigningKeys 的 main 生成密钥", e);
        } catch (ParseException e) {
            throw new IllegalStateException("jwk/rs256.json 不是合法的 RSA JWK", e);
        }
    }

    public String keyId() { return jwk.getKeyID(); }
    public RSAPublicKey publicKey() { ... }
    public RSAPrivateKey privateKey() { ... }
}
```

```java
@Configuration
public class TokenEncoderConfig {

    @Bean
    JwtEncoder jwtEncoder(SigningKeyProvider keys) {
        return NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                // withKeyPair 构造的内部 JWK 默认没有 kid，而签出的令牌头里带 kid，
                // 编码器按键选择 JWK，两边不一致会报 Failed to select a JWK signing key
                .jwkPostProcessor(jwk -> jwk.keyID(keys.keyId()))
                .build();
    }
}
```

`jwkPostProcessor` 这一行是我实测踩出来的：令牌头里写了 kid，编码器按头里的 kid 去密钥源里选钥匙，`withKeyPair` 生成的内部 JWK 恰好没有 kid，选不中就抛 `Failed to select a JWK signing key`。把 kid 补进 JWK，两边对齐，问题当场消失。

### 自签发：claim 结构与生产一致

TokenService 负责把 subject、scope、audience、有效期组装成令牌，演示端点与测试都从这里拿令牌：

```java
public Jwt issue(String subject, List<String> scopes, String audience, Duration ttl) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("http://order-service.internal")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString())
            .claim("scope", scopes);
    if (audience != null && !audience.isBlank()) {
        claims.audience(List.of(audience));
    }
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .keyId(keys.keyId())
            .type("JWT")
            .build();
    Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims.build()));
    log.info("JWT_EVENT issued subject={} scope={} expiresAt={}",
            subject, String.join(",", scopes), expiresAt);
    return jwt;
}
```

scope 我直接用 List，校验侧的默认 converter 对空格分隔字符串与 List 两种形态都认，List 在解码后更直观。audience 允许传 null，签出的令牌不带 aud claim，专门用来验证受众校验。真实系统的签发方是独立授权服务器，本工程签发与校验共用同一对密钥做完整演示，claim 结构与生产一致，换到真实 IdP 时只换签发方，校验侧代码一行不动。

### scope 映射与 @PreAuthorize

scope claim 变成 authority 之后，敏感操作就能在服务层方法上收敛了：

```java
@PreAuthorize("hasAuthority('SCOPE_orders:read')")
public List<Order> list() {
    return store.all();
}

@PreAuthorize("hasAuthority('SCOPE_orders:write')")
public Order create(String orderNo, BigDecimal amount) {
    Order order = store.create(orderNo, amount, currentUser());
    log.info("ORDER_EVENT created orderNo={} by={} count={}",
            order.orderNo(), order.createdBy(), store.all().size());
    return order;
}
```

`SCOPE_` 前缀来自默认 converter，表达式里的前缀与 application.yml 的 `authority-prefix`（默认值就是 `SCOPE_`）是同一个口径，改一边必须改另一边。`currentUser()` 取的是认证主名，来源是 sub claim，所以建单记录里的操作人是 `reports` 这样的机器身份，没有人工账号混在里面。`@EnableMethodSecurity` 要贴在 SecurityConfig 上，少了它所有 `@PreAuthorize` 静默失效，我第一次联调就栽在这。

### 演示令牌端点：client_credentials 语义

真实部署里 POST /oauth2/token 属于授权服务器。本工程为了自签环回，我把这个端点做进了订单服务，校验 client_id 与 client_secret 后签发 JWT：

```java
@PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public ResponseEntity<?> token(@RequestParam Map<String, String> form,
                               HttpServletRequest request) {
    if (!"client_credentials".equals(form.get("grant_type"))) {
        return problem(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                "grant_type 只支持 client_credentials");
    }
    String[] basic = basicCredentials(request);
    String clientId = basic != null ? basic[0] : form.get("client_id");
    String clientSecret = basic != null ? basic[1] : form.get("client_secret");
    ClientEntry entry = clientId == null ? null : CLIENTS.get(clientId);
    // MessageDigest.isEqual 做常量时间比较，避免逐字符比较泄露时序信息
    if (entry == null || clientSecret == null
            || !MessageDigest.isEqual(entry.secret().getBytes(StandardCharsets.UTF_8),
                    clientSecret.getBytes(StandardCharsets.UTF_8))) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid_client", "client_id 或 client_secret 不正确");
    }
    List<String> requested = form.containsKey("scope")
            ? List.of(form.get("scope").trim().split("\\s+"))
            : entry.scopes();
    if (!entry.scopes().containsAll(requested)) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_scope",
                "请求的 scope 超出该客户端的注册范围");
    }
    Jwt jwt = tokens.issue(clientId, requested, TokenService.AUDIENCE, Duration.ofSeconds(ttl));
    return ResponseEntity.ok(Map.of(
            "access_token", jwt.getTokenValue(),
            "token_type", "Bearer",
            "expires_in", ttl,
            "scope", String.join(" ", requested)));
}
```

客户端认证支持 HTTP Basic 与表单字段两种来源，oauth2-client 默认发 Basic，curl 演示也顺手。secret 比较我用 `MessageDigest.isEqual`，常量时间完成，不给逐位猜测留时序线索。请求的 scope 是注册 scope 的子集才放行，logistics 想要 orders:write 会被 invalid_scope 挡回来。

### oauth2-client：RestClient 挂 AuthorizedClientManager

机器调用方这一侧，RestClient 的拦截器每次请求前向 AuthorizedClientManager 要令牌，缓存没有或临近过期时管理器自动走 client_credentials 换新的：

```java
@Bean
OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations) {
    OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials()
            .build();
    AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                    registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
    manager.setAuthorizedClientProvider(provider);
    return manager;
}

@Bean
RestClient logisticsRestClient(OAuth2AuthorizedClientManager manager,
                               @Value("${app.base-url:http://localhost:18190}") String baseUrl) {
    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestInterceptor(new BearerInterceptor(manager, "logistics", "logistics-cron"))
            .build();
}
```

BearerInterceptor 的核心逻辑三行：

```java
OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
        .withClientRegistrationId(registrationId)
        .principal(principal)
        .build());
if (client == null) {
    throw new IllegalStateException("未取得 client credentials 令牌: " + registrationId);
}
request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
```

registration 的 client-id、client-secret、token-uri 都来自 application.yml，`principal` 在 client_credentials 场景只是个缓存键。启动时带 m2m-demo profile 跑一个 ApplicationRunner，logisticsRestClient 真打一次受保护接口，日志落 M2M_CALL 行，就是客户端链路通了的对账依据。

## 完整案例

一轮实测的场景与结果都在这里。先换令牌，reports 客户端用 Basic 认证发 client_credentials 请求：

```text
POST /oauth2/token   reports + 正确密钥   -> 200   签发耗时 12.6ms，令牌长 675 字符
POST /oauth2/token   reports + 错误密钥   -> 401   invalid_client
```

![](https://static.xiongneng.me/oauth2-token-panels-20260920115640.png)

access_token 响应体逐字如下，expires_in 600 秒：

```json
{"access_token":"eyJraWQiOiJvcmRlci0xYTBiY2RiNzBiNyIsInR5cCI6IkpXVCIsImFsZyI6IlJTMjU2In0.eyJpc3MiOiJodHRwOi8vb3JkZXItc2VydmljZS5pbnRlcm5hbCIsInN1YiI6InJlcG9ydHMiLCJpYXQiOjE3ODk4NzU1OTYsImV4cCI6MTc4OTg3NjE5NiwianRpIjoiYzQ4NTRiMDQtZjYzYS00ZWI5LTk5MmUtNTUzY2IzMzE1ZjI2IiwiYXVkIjoib3JkZXItc2VydmljZSIsInNjb3BlIjpbIm9yZGVyczpyZWFkIiwib3JkZXJzOndyaXRlIl19.xxx","token_type":"Bearer","expires_in":600,"scope":"orders:read orders:write"}
```

拿这个令牌打订单接口，三种结果各有归属：

```text
GET  /api/orders   reports 令牌（read+write）  -> 200   往返 25.4ms
POST /api/orders   reports 令牌（read+write）  -> 201   createdBy=reports
POST /api/orders   logistics 令牌（只有 read） -> 403   scope 不足
GET  /api/orders   无令牌                      -> 401   WWW-Authenticate: Bearer
```

![](https://static.xiongneng.me/oauth2-result-panels-20260920115646.png)

201 与 403 的响应体逐字如下，问题类别用 type 区分。**401 与 403 的分界在令牌：拿不出有效令牌是 401，令牌有效但 scope 不够才是 403。**

```json
{"orderNo":"SK-OA2-1","amount":359.00,"status":"NEW","createdBy":"reports","createdAt":"2026-09-20T03:56:42.009142Z"}
```

```json
{"detail":"当前令牌的 scope 不足以访问该资源","instance":"/api/orders","status":403,"title":"无权访问","type":"urn:problem-type:forbidden"}
```

过期与篡改两个失败分支我也各实测了一次，这里有个挺有意思的反直觉结果，单独说：ttl_seconds=1 的令牌过期 1.5 秒后请求仍是 200，我等到 62 秒才看到 401，`WWW-Authenticate: Bearer realm="order-service", error="invalid_token"`。判断也现成：这是校验器给集群时钟误差留的余量，避坑指南坑一细说。签名段首字符替换后请求 401，与过期令牌同一个 problem+json 出口。

令牌的 payload 用 base64 解开，sub、aud、scope、exp 全部实录：

```json
{"sub":"reports","aud":"order-service","scope":["orders:read","orders:write"],"iss":"http://order-service.internal","exp":1789877197,"iat":1789876597,"jti":"38f73651-7afa-4f6f-9ff2-050019922e10"}
```

![](https://static.xiongneng.me/oauth2-claims-panel-20260920115647.png)

aud 与 application.yml 的 audiences 对得上，scope 两个值对应两条 SCOPE_ authority，exp 减 iat 正好 600 秒，claims 三处交叉验证一致。日志对账我也逐项核过：JWT_EVENT issued 4 行（reports 与 logistics 的换令牌各 1 行、ttl_seconds=1 的过期令牌 1 行、启动环回 1 行），M2M_CALL 1 行 `client=logistics status=200 elapsedMs=248`，整个运行 ERROR 0 行。

## 测试怎么写

四个测试类覆盖四种能力，12 个用例全绿：

```text
TokenServiceTest           2  编码侧：claim 结构、kid 头、编码器与解码器互解
JwtApiSecurityTest         4  jwt() 后处理器验 scope 映射与 401/403 分工
RealSignedJwtTest          5  真实签名链路：有效、过期、篡改、aud 不符、aud 缺失
ClientCredentialsLoopTest  1  client_credentials 全链路：换令牌、调用、缓存复用
```

授权规则的测试用 jwt() 请求后处理器，scope claim 直接写进用例：

```java
@Test
void read_scope_can_list_but_cannot_create() throws Exception {
    var read = jwt().jwt(j -> j.subject("logistics-cron")
            .claim("scope", List.of("orders:read")));

    mockMvc.perform(get("/api/orders").with(read))
            .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders").with(read)
                    .contentType("application/json")
                    .content("{\"orderNo\":\"SK-JWT-1\",\"amount\":99.50}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"));
}
```

jwt() 把 JwtAuthenticationToken 直接放进安全上下文，不经过解码器，默认按 scope claim 生成 SCOPE_ 前缀 authority，与资源服务器的映射行为一致。**jwt() 验授权规则，令牌自身的校验链路（过期、签名、受众）必须用签发出的真 JWT 单独测一遍。** 所以真实签名链路我单独拆了一类：

```java
@Test
void wrong_audience_gets_401() throws Exception {
    Jwt other = tokens.issue("reports-cron", List.of("orders:read"),
            "other-service", Duration.ofSeconds(600));

    mockMvc.perform(get("/api/orders").header("Authorization", bearer(other)))
            .andExpect(status().isUnauthorized());
}
```

TokenService 签出的真令牌走完整的 BearerTokenAuthenticationFilter 到 Boot 自动装配的 JwtDecoder，aud 换成 other-service 后被自动挂上的 JwtClaimValidator 拒掉。过期令牌用 TokenService 的另一个重载签发，签发时间与过期时间都放到过去。客户端链路用一个测试类收口：测试里程序化构造 ClientRegistration 指向真实运行的随机端口，AuthorizedClientManager 换真令牌，RestClient 拦截器挂 Bearer 打受保护接口，两次调用断言拿到同一个令牌，缓存复用就得到了验证。

## 避坑指南

**坑一，过期令牌默认还能用 60 秒。** JwtTimestampValidator 的静态初始化字节码是 `Duration.of(60, SECONDS)`，默认时钟偏移容忍。我实测 ttl_seconds=1 的令牌过期 1.5 秒后请求照样 200，越过偏移窗口才 401。这是给集群时钟误差留的余量，生产排障时「过期令牌还能用一分钟」属于预期行为，默认值改不了，Decoder 想收紧要自己换 validator。

**坑二，withKeyPair 的编码器报 Failed to select a JWK signing key。** 令牌头里写了 kid 时，编码器按键选 JWK，`NimbusJwtEncoder.withKeyPair` 构造的内部 JWK 默认没有 kid，选不中直接抛这个异常。解法是 `jwkPostProcessor(jwk -> jwk.keyID(...))` 把 kid 补进 JWK，或者头里干脆不写 kid。

**坑三，自建 token-uri 的 registration 启动即报 Provider ID must be specified。** oauth2-client 的 registration 不指向 GitHub、Google 这类内置供应商时，必须先在 `spring.security.oauth2.client.provider.<id>` 下声明，再在 registration 里用 `provider: <id>` 引用。直接在 registration 里写 token-uri 不算声明 provider，启动校验过不去。

**坑四，属性名是 public-key-location，不是 public-key。** 类型是 `org.springframework.core.io.Resource`，`classpath:jwk/public.pem` 直接可用，PEM 文本按 X.509 SubjectPublicKeyInfo 解析。属性名写短了，Boot 当成未知属性静默忽略，第一条受保护请求报 NoSuchBeanDefinitionException，错误信息还不指向配置，我排查时绕了一圈才对上。

**坑五，@PreAuthorize 的 SCOPE_ 前缀要与 authority-prefix 对齐。** 默认 converter 把 scope claim 的每个值映射成 `SCOPE_` 前缀 authority，表达式写 `hasAuthority('SCOPE_orders:write')`。配置里改了 authority-prefix 而表达式没跟着改，或者反过来，结果都是任何令牌都匹配不上，受保护接口清一色 403。

**坑六，编码器签不出 exp 早于 iat 的令牌。** Jwt 构造时断言 expiresAt must be after issuedAt，想测过期令牌，不能只把有效期改成负数，要用支持指定签发时间的重载把 iat 也放到过去。测试里两个时间一起前移，签出的令牌才能走到校验侧的 exp 判断。

**坑七，篡改检测要改签名的有效位。** base64url 编码 256 字节签名后，末字符只承载 2 个有效位，低 4 位是填充，替换末字符解出来的字节可能一模一样，校验照样通过。测篡改场景要改签名段首个字符，6 个位全是有效位，必然解码出不同的签名。

**坑八，@AutoConfigureMockMvc 在 Boot 4 换了包。** 全限定名是 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`，它随 spring-boot-starter-webmvc-test 进来，security-test starter 不含注解本体。两个测试 starter 都要引，只引一个就是编译错误或注解找不到。

**坑九，过滤器层的 401/403 响应体要自己写。** 令牌校验发生在 DispatcherServlet 之前，`spring.mvc.problemdetails.enabled` 与 @RestControllerAdvice 都够不着。自定义 AuthenticationEntryPoint 与 AccessDeniedHandler，401 的响应头带 `WWW-Authenticate: Bearer`，校验失败追加 `error="invalid_token"`，调用方才拿得到重试需要的信号。

## 小结

选型建议一句话：调用方全是机器，就用 client_credentials 加资源服务器这套组合，Boot 4.1 里它已经收敛到接近零配置，校验侧两行属性加一个 SecurityConfig，签发侧一个 JwtEncoder 加一个 TokenService。哪天接入独立 IdP，校验侧换成 jwk-set-uri、签发方指过去就行，业务代码与权限注解都不用动。多客户端的权限差异交给 scope 表达，别再让几个系统共用一个密码。

没解决的事也直说：演示令牌端点做在订单服务进程里，授权码、refresh_token 这类交互式授权没有覆盖；令牌吊销同样没做，签出去的令牌在有效期内收不回，目前只能靠密钥轮换兜底。这两块哪天补齐了我再写。

## 参考链接

- [Spring Security - OAuth2 Resource Server](https://docs.spring.io/security/reference/servlet/oauth2/resource-server/jwt.html)：JWT 解码器、校验器与 authority 映射的官方文档，本文校验链路的依据
- [Spring Security - OAuth2 Client](https://docs.spring.io/security/reference/servlet/oauth2/client.html)：AuthorizedClientManager 与 client_credentials 授权模式的官方说明
- [Spring Boot Reference - OAuth2](https://docs.spring.io/spring-boot/reference/web/security.html)：Boot 侧自动配置与 spring.security.oauth2.* 属性说明
- [RFC 6750 - Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750)：WWW-Authenticate 与 error 码的规范依据
- [RFC 7519 - JSON Web Token](https://www.rfc-editor.org/rfc/rfc7519)：JWT 的 claim 定义（iss、sub、aud、exp、jti）
- [nimbus-jose-jwt](https://connect2id.com/products/nimbus-jose-jwt)：底层 JOSE/JWT 库，RSAKeyGenerator 与 JWK 解析的文档
- [spring-boot-starter-security-oauth2-resource-server 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-security-oauth2-resource-server/4.1.1/)：本文 starter 组成与属性结论的制品实证来源
