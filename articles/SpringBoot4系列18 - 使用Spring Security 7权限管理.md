---
title: SpringBoot4系列18 - 使用Spring Security 7权限管理
slug: sb4-security
date: 2026-10-24 20:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Spring Security, 权限管理 ]
draft: false
---

运营后台的权限需求我接到过不止一次：客服查订单，管理员取消订单、翻审计。早年我偷懒，在每个接口开头手写身份判断，`if (!currentUser().isAdmin())` 复制到十几个方法里，后来加一个角色要全局搜一遍 if，说实话我自己都说不清某个接口到底谁能调。身份认证与授权是「所有请求进业务之前都要过一遍」的横切关卡，这类活儿交给 Spring Security 的过滤器链正合适，我在这篇里把整套方案在 Spring Boot 4.1.1 加 Spring Security 7.1.1 上完整落了一遍。

![](https://static.xiongneng.me/security-filter-sequence-20260922041441.png)

本文的工程是一个运营后台：SecurityFilterChain 按路径分角色，用户落 MySQL 存 `{bcrypt}` 密文，取消订单这类敏感操作收敛在服务层 `@PreAuthorize`，401 与 403 用 RFC 9457 风格的响应体区分开。18 个测试全绿，正文里的数字全部来自同一次运行，我留了底，你可以逐项对。

## 编程模型与自动配置的事实

写代码之前我习惯先把坐标、自动配置与 DSL 的现状确认清楚，免得照着过时的示例写。以下结论全部来自 Central 制品解包与 javap 实测。

Security 7 时代 starter 坐标没改名，`spring-boot-starter-security` 在 4.1.1 的 Central 目录里正常存在。组成我解包核对过（POM 实测）：

```text
spring-boot-starter-security      = spring-boot-starter + spring-boot-security
                                    + spring-aop 7.0.9
spring-boot-security              = spring-boot + spring-security-config 7.1.1
                                    + spring-security-web 7.1.1
spring-security-config            = spring-security-core（core 再带 spring-security-crypto）
spring-boot-starter-security-test = spring-boot-starter-security + spring-boot-starter-test
                                    + spring-boot-security-test
```

有两个细节我专门记了一笔。其一，Boot 4 把安全自动配置代码放进了独立模块 `spring-boot-security`，包名是模块化的 `org.springframework.boot.security.autoconfigure`，该模块的 AutoConfiguration.imports 我数了一遍共 10 条，和 Servlet 工程直接相关的 4 条是：

```text
org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
```

其二，spring-security-crypto 不在 starter 的直接依赖里，靠 config 到 core 再到 crypto 两跳传递进来。BCryptPasswordEncoder 运行期直接可用，不用额外引依赖，这是我解包时才确认的。

默认链的退让条件用 javap 解出来是两层。外层 ServletWebSecurityAutoConfiguration 标着 `@ConditionalOnDefaultWebSecurity`，内部再分两个配置类：

```text
ServletWebSecurityAutoConfiguration            @ConditionalOnDefaultWebSecurity
  ├─ EnableWebSecurityConfiguration            贴 @EnableWebSecurity
  └─ SecurityFilterChainConfiguration
        @Bean defaultSecurityFilterChain(HttpSecurity)     @Order(2147483642)

@ConditionalOnDefaultWebSecurity = DefaultWebSecurityCondition（AllNestedConditions）
  ├─ Beans   @ConditionalOnMissingBean(SecurityFilterChain)
  └─ Classes @ConditionalOnClass({SecurityFilterChain, HttpSecurity})
```

**工程里只要声明一个 SecurityFilterChain bean，Boot 的默认链整条退让，@EnableWebSecurity 也不用自己贴。** 这里最容易读错的是退让粒度：EnableWebSecurityConfiguration 只按 bean 名 springSecurityFilterChain 退让，跟工程里自定义链的 bean 名无关，所以自定义链和它并不冲突，注解贴不贴都在。我第一遍读源码时把这条粒度理解错了，白琢磨了十分钟。

`spring.security.*` 前缀下的属性只有 5 条（spring-configuration-metadata 实证），比我想的少得多：

```text
spring.security.filter.dispatcher-types   默认 [async, error, forward, include, request]
spring.security.filter.order              默认 -100
spring.security.user.name                 默认 user
spring.security.user.password             默认无（不配则启动日志打印随机密码）
spring.security.user.roles                默认无
```

`user.*` 三条归 UserDetailsServiceAutoConfiguration 消费：工程没有自己的 UserDetailsService bean 时，Boot 按它们造一个内存用户。DSL 现状也确认过：Security 7 的 HttpSecurity 上 authorizeHttpRequests、csrf、sessionManagement、formLogin、httpBasic、exceptionHandling 全部是 Customizer lambda 风格，没有 and() 链尾；路径匹配主力是 PathPatternRequestMatcher，位于 `org.springframework.security.web.servlet.util.matcher`。

![](https://static.xiongneng.me/security-starter-map-20260922041538.png)

## 依赖和配置

工程四块依赖，是我按职责挑的：starter-webmvc 撑起 HTTP 接口面，starter-security 是本篇主角，starter-jdbc 加 MySQL 驱动管用户表与审计表落库：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

测试侧两个 starter，缺一不可，这个我踩过：security-test 提供 `@WithMockUser` 与 httpBasic 请求后处理器，但不含 `@AutoConfigureMockMvc` 本体，HTTP 级测试要另加 webmvc-test：

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

配置的关键一处是 `spring.mvc.problemdetails.enabled=true`，业务异常的 404 与 409 统一走 RFC 9457 的 `application/problem+json`。注意它管不到安全过滤器层的 401 与 403，原因在核心代码一节展开：

```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.1.97:3306/springboot4_security?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root123456
  mvc:
    problemdetails:
      enabled: true
  sql:
    init:
      mode: always
      schema-locations: classpath:sql/schema.sql
```

表两张。sec_users 存登录名、密文、启用位与角色，t_audit_log 留敏感操作审计：

```sql
CREATE TABLE IF NOT EXISTS sec_users
(
    username VARCHAR(64)  NOT NULL COMMENT '登录名',
    password VARCHAR(100) NOT NULL COMMENT '密文，带 {bcrypt} 前缀',
    enabled  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 可用 0 停用',
    role     VARCHAR(32)  NOT NULL COMMENT '角色名，不带 ROLE_ 前缀：ADMIN / OPERATOR',
    PRIMARY KEY (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_audit_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(64)  NOT NULL COMMENT '操作人',
    action     VARCHAR(64)  NOT NULL COMMENT '操作名',
    order_no   VARCHAR(32)  DEFAULT NULL COMMENT '关联订单号',
    result     VARCHAR(16)  NOT NULL COMMENT 'OK / DENIED / ERROR',
    detail     VARCHAR(200) DEFAULT NULL COMMENT '补充说明',
    created_at DATETIME(3)  NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

## 核心代码

![](https://static.xiongneng.me/security-filter-component-20260922041320.png)

### 过滤器链：一处声明管住全部 HTTP 面

一个 SecurityConfig 类装下路径级授权、认证方式与会话策略，重点看 authorizeHttpRequests 的三条规则与 httpBasic 的 entry point 挂法：

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemWriter problems) throws Exception {
        http
            // 纯 API + STATELESS 会话：没有浏览器表单、没有 Cookie 会话，
            // CSRF 保护没有作用对象
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // 审计记录只有管理员能读
                    .requestMatchers("/api/audit/**").hasRole("ADMIN")
                    // 订单查询与操作两种角色都放行，
                    // ADMIN 之外的收敛在服务层方法上做
                    .requestMatchers("/api/orders/**").hasAnyRole("OPERATOR", "ADMIN")
                    .anyRequest().authenticated())
            // 取舍：管理后台是纯 API，调用方是前端脚本与运维脚本，没有登录页可跳，
            // formLogin 的默认登录页与重定向对脚本不友好；httpBasic 每次请求都带凭据，
            // 无状态干净。entry point 必须挂在 httpBasic 上，原因见避坑指南坑一
            .httpBasic(basic -> basic.authenticationEntryPoint(problems::unauthorized))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(problems::unauthorized)
                    .accessDeniedHandler(problems::forbidden));
        return http.build();
    }
}
```

三条规则按具体路径在前、anyRequest 收尾的顺序排，先匹配到的先生效。认证方式在 httpBasic 与 formLogin 之间我选了 httpBasic：formLogin 面向浏览器，未登录时 302 跳登录页，对脚本调用方是灾难；httpBasic 未登录直接 401，配合 STATELESS 会话策略，服务端不留任何会话状态。两个自定义 Bean 接着登场：

```java
@Bean
PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}

@Bean
UserDetailsManager userDetailsService(DataSource dataSource) {
    JdbcUserDetailsManager users = new JdbcUserDetailsManager(dataSource);
    users.setUsersByUsernameQuery(
            "SELECT username, password, enabled FROM sec_users WHERE username = ?");
    users.setAuthoritiesByUsernameQuery(
            "SELECT username, CONCAT('ROLE_', role) FROM sec_users WHERE username = ?");
    return users;
}
```

PasswordEncoder 用 DelegatingPasswordEncoder，按密文前缀 `{bcrypt}`、`{noop}`、`{pbkdf2}` 分发算法，新密文 encode 出来默认落 `{bcrypt}`。UserDetailsService 声明成 JDBC 版本后，Boot 的 UserDetailsServiceAutoConfiguration（`spring.security.user.*` 内存用户）整体退让。authorities 查询里 `CONCAT('ROLE_', role)` 是在补角色前缀，数据库存 `ADMIN`，授权表达式 `hasRole("ADMIN")` 内部按 `ROLE_ADMIN` 比较，两边口径必须对齐。

### 用户与密码：JDBC 用户加 {bcrypt} 密文

注册在我这里就是 BCrypt 编码后入库，明文只出现在种用户的一处代码里：

```java
private void seed(String username, String rawPassword, String role) {
    Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sec_users WHERE username = ?", Integer.class, username);
    if (exists != null && exists > 0) {
        // 已有账号只打日志不覆盖（INSERT-OR-LOG 语义）
        return;
    }
    String hash = encoder.encode(rawPassword);
    jdbc.update("INSERT INTO sec_users(username, password, enabled, role) VALUES (?, ?, 1, ?)",
            username, hash, role);
    log.info("SEED_USER inserted username={} hash={} role={}", username, hash, role);
}
```

启动后 sec_users 里落了两行，密文取自实测日志 `SEED_USER`：

```text
SEED_USER inserted username=admin    hash={bcrypt}$2a$10$NEA3Qmb8lB/0uKs96OH1zugsASxwZ81G3F2uYnv/NnLmOsvc0z33O role=ADMIN
SEED_USER inserted username=operator hash={bcrypt}$2a$10$9RLVpDGRVOCL2q5fjClKnuWRNlfCMw4IiUNFUYnR37GdWRooZe4Yy role=OPERATOR
```

密文前缀 `{bcrypt}$2a$10$` 里藏着两个事实：算法是 bcrypt，强度是 10。强度不是配置出来的，是 BCryptPasswordEncoder 无参构造的默认值，javap 反汇编里 `iconst_m1` 之后换算成 log rounds 10。**库里永远存带 {id} 前缀的密文，裸的 $2a$10$ 字符串没有前缀，认证时直接抛异常。** 挺有意思的一点：BCrypt 自带的盐让同一明文每次编码结果都不同，我两次启动种出的密文不一样，认证照样通过，这是 matches 按盐重算的功劳，不是数据库里存了两个密码。

### 401 与 403：过滤器层的 problem+json

安全过滤器跑在 DispatcherServlet 之前，`spring.mvc.problemdetails.enabled` 管不到它们，401 与 403 的响应体要自己写，这是我一开始没料到的。自定义一个组件同时实现两种出口：

```java
public void unauthorized(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
    ProblemDetail pd = problem(HttpStatus.UNAUTHORIZED, "未登录", request,
            "请求缺少有效的 Basic 凭据或凭据无效");
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    // Basic 认证的 401 必须带 WWW-Authenticate，客户端靠它知道该用哪种方式认证
    response.setHeader("WWW-Authenticate", "Basic realm=\"ops-console\"");
    write(response, pd);
}

public void forbidden(HttpServletRequest request, HttpServletResponse response,
                      AccessDeniedException ex) throws IOException {
    ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "无权访问", request,
            "当前账号的角色不足以访问该资源");
    response.setStatus(HttpStatus.FORBIDDEN.value());
    write(response, pd);
}
```

序列化用 Jackson 3 的 JsonMapper，构造时关掉 null 字段输出，ProblemDetail 没设置 properties 时响应体里就不会出现 `"properties": null`。type 字段按状态码映射成 `urn:problem-type:unauthorized` 与 `urn:problem-type:forbidden`，和 MVC 层渲染的业务异常响应体保持同一套风格。

**401 与 403 的分界在认证：拿不出有效身份是 401，认证通过但角色不够才是 403。** 用户名不存在对外伪装成凭据错误，同样落 401，不给探测账号清单的调用方留线索。

### 方法级：@PreAuthorize 收敛敏感操作

路径级规则把 `/api/orders/**` 放给 OPERATOR 之后，取消订单这种敏感操作要在更细的粒度收敛回 ADMIN：

```java
@PreAuthorize("hasRole('ADMIN')")
public Order cancel(String orderNo) {
    Order order = store.get(orderNo);
    if (Order.STATUS_CANCELLED.equals(order.status())) {
        return order;
    }
    Order cancelled = new Order(order.orderNo(), order.amount(), Order.STATUS_CANCELLED,
            order.createdBy(), order.createdAt());
    store.update(cancelled);
    audit.record(currentUser(), "取消订单", orderNo, "OK", "订单已取消");
    log.info("ORDER_EVENT cancelled orderNo={} by={}", orderNo, currentUser());
    return cancelled;
}
```

注解拦在方法代理上，比控制器更靠近业务：就算调用方绕过 HTTP 直接注入 OrderService bean 调 cancel，代理照样抛 AccessDeniedException，我写了专门的单测验证这一点。**路径级规则管进门，方法级注解管办事，两层各管一段。** 配套的 `@EnableMethodSecurity` 已经贴在 SecurityConfig 类上，少这一个注解，所有 `@PreAuthorize` 静默失效。

### 认证事件：成功与失败都有日志行

ProviderManager 在认证出结果时发布事件，我监听成对的两个类型就够了：

```java
@EventListener
public void onSuccess(AuthenticationSuccessEvent event) {
    log.info("AUTH_EVENT success principal={} type={}",
            event.getAuthentication().getName(),
            event.getAuthentication().getClass().getSimpleName());
}

@EventListener
public void onFailure(AbstractAuthenticationFailureEvent event) {
    log.info("AUTH_EVENT failure principal={} reason={}",
            event.getAuthentication() == null ? "unknown" : event.getAuthentication().getName(),
            event.getException().getClass().getSimpleName());
}
```

两个事件类型都在 `org.springframework.security.authentication.event` 包。AUTH_EVENT 前缀的日志行是后面验证脚本与测试的对账依据，认证失败的原因（BadCredentialsException 这类）直接进了日志，安全告警可以按行订阅。

## 完整案例

我给这个运营后台定了一个最小权限矩阵：

| 接口 | OPERATOR | ADMIN |
|---|---|---|
| GET /api/orders（订单列表） | 200 | 200 |
| POST /api/orders（创建订单） | 201 | 201 |
| POST /api/orders/{no}/cancel（取消订单） | 403 | 200 |
| GET /api/audit（审计查询） | 403 | 200 |

启动应用，清空两张表，按场景打一轮。先看被拦下的三种：

```text
GET  /api/orders                              匿名            -> 401
GET  /api/audit                               operator        -> 403
POST /api/orders/SK-SEC-1/cancel              operator        -> 403（方法级）
POST /api/audit                               admin + 错密码   -> 401
```

![](https://static.xiongneng.me/security-denied-panels-20260920103537.png)

匿名请求的 401 与越权请求的 403 响应体逐字如下，instance 指向被拦的 URI，type 区分问题类别：

```json
{"detail":"请求缺少有效的 Basic 凭据或凭据无效","instance":"/api/orders","status":401,"title":"未登录","type":"urn:problem-type:unauthorized"}
```

```json
{"detail":"当前账号的角色不足以访问该资源","instance":"/api/audit","status":403,"title":"无权访问","type":"urn:problem-type:forbidden"}
```

401 的响应头带着 `WWW-Authenticate: Basic realm="ops-console"`，客户端靠它知道该补 Basic 凭据。两个 403 值得对照：读审计的 403 拦在过滤器层（路径级规则），取消订单的 403 拦在服务层方法代理（`@PreAuthorize`），出口都归到同一个 AccessDeniedHandler。同一个状态码，两层拦截点，排查时先看是哪一层拦的，能省一半时间。

再看放行的部分：

```text
POST /api/orders  {"orderNo":"SK-SEC-2","amount":299.00}   operator -> 201
POST /api/orders/SK-SEC-2/cancel                           admin    -> 200
GET  /api/audit                                            admin    -> 200
```

![](https://static.xiongneng.me/security-granted-panels-20260920103542.png)

建单响应里 createdBy 是 operator，取消响应里 cancelledBy 是 admin，取消成功后审计表落了 1 行：

```json
{"cancelledBy":"admin","orderNo":"SK-SEC-2","status":"CANCELLED"}
```

```json
[{"id":1,"username":"admin","action":"取消订单","orderNo":"SK-SEC-2","result":"OK","detail":"订单已取消","createdAt":"2026-09-20T10:35:39.603"}]
```

![](https://static.xiongneng.me/security-audit-dataflow-20260922041517.png)

审计行的数据流跟着取消操作走：@PreAuthorize 放行，业务改订单状态，AuditService 写库，id 从 1 开始说明是本轮首行。日志对账三项全对上：SEED_USER 2 行（两个账号的密文如上），AUTH_EVENT success 7 行、failure 1 行（错误密码那次），整个运行 ERROR 0 行。

## 测试怎么写

四个测试类覆盖四种能力，18 个用例全绿：

```text
PasswordEncodingTest   4 个  BCrypt 编码与匹配、{noop} 前缀分发、库里密文实测
OrderApiSecurityTest   7 个  匿名/OPERATOR/ADMIN 三种身份打三类接口的全景
HttpBasicAuthTest      5 个  真实凭据链路：Basic -> JDBC 用户 -> {bcrypt} 密文
MethodSecurityTest     2 个  绕过 HTTP 直接调服务层，方法代理照样拦
```

真实认证链路用 httpBasic 请求后处理器打，凭据错误时连响应体和事件一起断言：

```java
@Test
void wrong_password_gets_401_with_failure_event(CapturedOutput output) throws Exception {
    mockMvc.perform(get("/api/audit").with(httpBasic("admin", "badpass")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"))
            .andExpect(jsonPath("$.title").value("未登录"));

    assertThat(output).contains("AUTH_EVENT failure principal=admin");
    assertThat(output).contains("reason=BadCredentialsException");
}
```

这条测试同时验证了三件事：401 的 problem+json 响应体、失败事件发布、错误原因进日志。方法级安全的第二现场不经过 HTTP，`@WithMockUser` 把身份放进 SecurityContext 后直接调 bean：

```java
@Test
@WithMockUser(username = "op1", roles = "OPERATOR")
void direct_service_call_is_stopped_for_operator() {
    assertThatThrownBy(() -> orderService.cancel("SK-DIRECT-1"))
            .isInstanceOf(AccessDeniedException.class);
}
```

**@WithMockUser 验授权规则，真实凭据链路要单独测。** 它直接把身份塞进上下文，不经过 UserDetailsService，JDBC 查询与 bcrypt 校验在这类测试里一次都没跑过，所以 HttpBasicAuthTest 用真凭据把整条链补上。两类测试我一开始想合一套里偷懒，后来发现它们根本不是一回事，分开学得踏实。

## 避坑指南

**坑一，Basic 凭据错误的 401 不走 exceptionHandling。** 这是最反直觉的一个：BasicAuthenticationFilter 认证失败时直接调自己构造时持有的 entry point，exceptionHandling 里配的 authenticationEntryPoint 拦不到这条路。解法是 entry point 挂两处：`.httpBasic(basic -> basic.authenticationEntryPoint(...))` 管凭据错误，exceptionHandling 管匿名访问与授权失败。少挂第一处时，错误密码的响应体是空的，我第一次跑就栽在这里，盯着空响应体查了半天。

**坑二，@AutoConfigureMockMvc 的包名要先核对。** 4.1.1 里它的全限定名是 `org.springframework.boot.webmvc.test.autoconfigure`，import 写错时 IDE 会提示同名注解不存在，很多人第一反应是 Boot 删了功能，先查包名再下结论。

**坑三，security-test starter 不含 @AutoConfigureMockMvc 本体。** spring-boot-starter-security-test 只把 Security 的 MockMvc 定制器登记进 AutoConfigureMockMvc.imports，注解本体在 webmvc-test 里。只引前者，测试类上贴 @AutoConfigureMockMvc 直接编译失败。

**坑四，@WithMockUser 不经过 UserDetailsService。** 它在测试启动时把 SecurityContext 直接放好，JDBC 用户查询、{bcrypt} 校验、DaoAuthenticationProvider 全部被跳过。授权规则（401/403 分工）用它测没问题，认证链路必须用 httpBasic 之类的真实凭据另测一遍，两类测试不能互相替代。

**坑五，角色前缀两边要对齐。** hasRole("ADMIN") 内部按 ROLE_ADMIN 比较，JdbcUserDetailsManager 的 authorities 查询里要显式 `CONCAT('ROLE_', role)`。数据库直接存 ROLE_ADMIN 再 CONCAT 一次，或者查询里忘了 CONCAT，结果都是任何角色都匹配不上，所有受保护接口清一色 403。

**坑六，裸 bcrypt 密文会让认证直接抛异常。** DelegatingPasswordEncoder 按密文前缀分发算法，库里存了不带 `{bcrypt}` 前缀的 `$2a$10$...` 时，认证阶段抛 `IllegalArgumentException: There is no PasswordEncoder mapped for the id "null"`。存量数据迁移时给每行密文补前缀，或者注册期统一走 DelegatingPasswordEncoder 的 encode。

**坑七，安全过滤器层的响应体要自己写。** 401 与 403 发生在 DispatcherServlet 之前，`spring.mvc.problemdetails.enabled` 与 @RestControllerAdvice 都够不着。自定义 AuthenticationEntryPoint 与 AccessDeniedHandler，用 ProblemDetail 组织内容再手工序列化，null 字段用 NON_NULL 收掉，否则响应体里出现 `"properties": null` 这类噪音。

**坑八，CSRF 关闭的前提是无表单加无会话。** 本工程 STATELESS 会话加纯 API，CSRF token 没有作用对象，disable 是有依据的取舍。带浏览器表单或 Cookie 会话的工程照抄这行 disable，等于把跨站请求伪造的防线拆了，这类工程该保留 CSRF 并按框架默认行为走。

## 小结

权限管理这件事，我的选型建议是按三层来收：路径级规则写进 SecurityFilterChain，管谁进得了门；用户来源交给一个 UserDetailsService bean，数据库加 DelegatingPasswordEncoder 是省心组合；服务层的敏感操作用 `@PreAuthorize` 收细粒度。自动配置能替你贴的注解就别自己贴，自定义链一出现默认链整条退让，内存用户那几条属性在没有自定义用户源的工程里才生效。碰到带浏览器表单的项目，把 CSRF 和 formLogin 留住，别照搬这篇的无状态配置。

没解决的事也直说：这套方案用的是 HTTP Basic，凭据随每个请求明文传输，生产环境必须压在 HTTPS 下面；登录态、token 签发、密码策略、账号锁定这些我都没碰，权限模型也只到角色这一层，数据级行权限是另一个量级的活儿。这些留给后面的篇章，等我把 OAuth2 资源服务器那条路走通再来对账。

## 参考链接

- [Spring Security - Servlet Architecture](https://docs.spring.io/security/reference/servlet/architecture.html)：过滤器链、FilterChainProxy 与认证机制的权威说明，本文时序图的依据
- [Spring Security - Authorization](https://docs.spring.io/security/reference/servlet/authorization/authorize-http-requests.html)：authorizeHttpRequests 与路径级授权规则的官方文档
- [Spring Security - Method Security](https://docs.spring.io/security/reference/servlet/authorization/method-security.html)：@PreAuthorize 与 @EnableMethodSecurity 的官方说明
- [Spring Boot Reference - Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html)：Boot 侧自动配置说明与 spring.security.* 属性
- [RFC 9457 - Problem Details](https://www.rfc-editor.org/rfc/rfc9457)：HTTP 问题详情规范，本文 401 与 403 响应体的格式依据
- [spring-boot-starter-security 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-security/4.1.1/)：本文 starter 组成与版本结论的制品实证来源
