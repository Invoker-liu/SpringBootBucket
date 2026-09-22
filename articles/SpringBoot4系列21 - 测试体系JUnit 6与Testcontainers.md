---
title: SpringBoot4系列21 - 测试体系JUnit 6与Testcontainers
slug: sb4-testing
date: 2026-09-21 00:30:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, JUnit, Testcontainers, 测试 ]
draft: false
---

先说一个我印象很深的场景：一家公司的订单服务上线两年，测试全靠打开浏览器手工点接口。下单、支付、查列表、删订单，一轮点下来四十分钟，改了支付逻辑还得把查询和删除再点一遍。一次完整回归半天起步，改一行代码不敢当天上线，攒到周五集中发布，发布窗口里谁也说不准哪条旧逻辑会被带崩。

我的解法是把测试建成体系，按层拆开：逻辑判断用单元测试钉住，接口契约用切片测试钉住，一条完整链路用端到端测试钉住，数据访问用真实数据库钉住。四层各管一段，改哪层跑哪层，回归时间从半天缩到一分钟。这篇我在 Spring Boot 4.1.1 上把整套东西搭了一遍：JUnit 6.0.3 做执行框架，Mockito 做替身，MockMvcTester 与 RestTestClient 做接口断言，Testcontainers 2.0.5 起一台真实 MySQL 跑数据层。30 个用例全部实跑过，数字全部来自同一次 mvn test，我留了底。

## 测试栈的事实清单

写代码前我先把依赖的来路确认清楚，下面这些全部来自 Central 制品解包与真实运行，不是我背出来的。

先说版本。Boot 4.1.1 的官方 BOM 管理的是 JUnit 6：spring-boot-dependencies 4.1.1 里 `junit-jupiter.version` 的值是 6.0.3，通过 junit-bom 6.0.3 引进来。测试代码的包名还是 org.junit.jupiter.api，@Test、@TestFactory、@TestInstance、@Nested、@RepeatedTest 都在这个包里，这是我解包 junit-jupiter-api-6.0.3.jar 看到的。

版本矩阵（BOM 条目实证）：

```text
JUnit 平台     junit-bom 6.0.3（junit-jupiter-api / params / engine 同版本）
断言库         assertj-core 3.27.7
替身库         mockito-core 5.23.0 + mockito-junit-jupiter
JSON 断言      json-path 2.10.0、jsonassert 1.5.3
等待库         awaitility 4.3.0
匹配器         hamcrest 3.0
XML 断言       xmlunit-core 2.11.0
容器测试       testcontainers-bom 2.0.5
```

spring-boot-starter-test 4.1.1 的组成（POM 解包）：

```text
spring-boot-starter-test 4.1.1
  = spring-boot-starter
  + spring-boot-test（@SpringBootTest 在 org.springframework.boot.test.context）
  + spring-boot-test-autoconfigure
  + junit-jupiter 6.0.3 + mockito 5.23.0 + assertj-core 3.27.7
  + spring-test 7.0.9 + spring-core
  + json-path + jsonassert + xmlunit-core + awaitility + hamcrest
  + jakarta.xml.bind-api
```

junit-jupiter 6.0.3 由 api、params、engine 三个构件组成，engine 依赖 junit-platform-engine 而不带 junit-platform-launcher。说实话，我第一反应是得在工程里手动补 launcher 依赖，翻完 BOM 才发现这步是多余的：Maven 侧的执行由 surefire 3.5.6 的 JUnit Platform Provider 补位，`mvn test` 直接可跑。

接口断言工具的包位置我逐个解包核对过：

```text
MockMvcTester      org.springframework.test.web.servlet.assertj     （spring-test 7.0.9）
RestTestClient     org.springframework.test.web.servlet.client      （spring-test 7.0.9）
@WebMvcTest        org.springframework.boot.webmvc.test.autoconfigure（spring-boot-webmvc-test 4.1.1）
@AutoConfigureMockMvc  org.springframework.boot.webmvc.test.autoconfigure
@SpringBootTest    org.springframework.boot.test.context            （spring-boot-test 4.1.1）
```

Boot 4 模块化之后，切片注解从 test-autoconfigure 拆到了各自的模块：webmvc 切片在 spring-boot-webmvc-test 里，对应 starter 坐标是 spring-boot-starter-webmvc-test。系列第 20 篇用过的 @SpringBatchTest 在 org.springframework.batch.test.context（spring-batch-test 6.0.5），照常可用。

Testcontainers 侧有个容易想错的点，我猜错过一次：Central 上不存在 spring-boot-starter-testcontainers 这个坐标（404 实证），Boot 与 Testcontainers 的接线走的是模块 org.springframework.boot:spring-boot-testcontainers，它只带 spring-boot-autoconfigure 和 testcontainers 2.0.5 两个依赖。接线注解的位置：

```text
@ServiceConnection      org.springframework.boot.testcontainers.service.connection
@ImportTestcontainers   org.springframework.boot.testcontainers.context
MySQLContainer          org.testcontainers.mysql                     （testcontainers-mysql 2.0.5）
@Testcontainers/@Container  org.testcontainers.junit.jupiter         （testcontainers-junit-jupiter 2.0.5）
```

MySQLContainer 在 Testcontainers 2.x 搬进了 org.testcontainers.mysql 包，旧包 org.testcontainers.containers 里留了一份兼容类；2.x 的 MySQLContainer 不再是泛型类，写 `MySQLContainer<?>` 编译不过，这是与 1.x 写代码时手感差别最大的一处。

![](https://static.xiongneng.me/testing-layers-pyramid-20260922050901.png)

## 依赖和配置

工程四个生产依赖加五个测试依赖。webmvc 撑接口面，validation 让创建请求的校验注解生效，jdbc 提供 JdbcClient 做订单数据层；H2 是本地运行与端到端测试的内存库，MySQL 驱动给容器用：

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
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

测试侧五件套，版本全部由 BOM 管理，一个版本号都不用写，这也是我推荐 starter-test 的最大理由：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

application.yml 两段。datasource 默认指向内存 H2（MySQL 兼容模式），数据层测试运行时这一条会被 @ServiceConnection 生成的 ConnectionDetails 覆盖，yml 一行都不用改，这个设计挺有意思：

```yaml
spring:
  application:
    name: springboot4-testing
  datasource:
    url: jdbc:h2:mem:orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE
    username: sa
    password: ""
  sql:
    init:
      mode: always

server:
  port: ${SERVER_PORT:18210}
```

schema.sql 一张表，H2 与 MySQL 都认这套语法：

```sql
CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no   VARCHAR(32)  NOT NULL UNIQUE,
    amount     DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
```

JUnit 6 的并行开关放在 src/test/resources/junit-platform.properties，类间并发、类内同线程。类内保持同线程，@Transactional 一类共享状态的写法不受影响；类间并发让五个测试类的 Spring 上下文同时启动，总时长由最慢的类决定：

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```

![](https://static.xiongneng.me/testing-test-stack-20260922050905.png)

## 分层核心代码

被测的订单业务共五个接口：POST /api/orders 建单（单号唯一，重复返回 409）、GET /api/orders 按状态过滤、POST /api/orders/{id}/pay 支付（仅 NEW 状态可支付）、DELETE /api/orders/{id} 删除、GET /api/orders/{id} 查详情。业务规则集中在 OrderService：建单查重、支付校验状态。

### 单元层：Mockito 替身加动态测试

单元层不碰 Spring 上下文，我用 Mockito 把 OrderRepository 换成替身，毫秒级出结果。四个用例覆盖建单查重与支付状态两条规则：

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    OrderRepository orders;

    @InjectMocks
    OrderService service;

    private static final Order SAMPLE =
            new Order(1L, "SK-T4-1", new BigDecimal("359.00"), OrderStatus.NEW, Instant.now());

    @Test
    void creates_order_with_new_status() {
        when(orders.existsByOrderNo("SK-T4-1")).thenReturn(false);
        when(orders.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        Order created = service.create("SK-T4-1", new BigDecimal("359.00"));

        assertThat(created.status()).isEqualTo(OrderStatus.NEW);
        verify(orders).insert(any());
    }

    @Test
    void refuses_to_pay_paid_order() {
        when(orders.findById(1L)).thenReturn(Optional.of(SAMPLE.paid()));

        assertThatThrownBy(() -> service.pay(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");
        verify(orders, never()).updateStatus(any(), any());
    }
}
```

creates_order_with_new_status 验证建单落库前状态是 NEW，refuses_to_pay_paid_order 验证 PAID 订单拒绝支付且从不触碰 updateStatus。verify(orders, never()) 这一行是我最看重的：业务规则在替身上断言，数据库坏了、没起、连不上，这层照常通过。

同一类里还有一组 @TestFactory 动态测试，把状态过滤的四种组合写成数据表，JUnit 6 的动态测试按行生成用例并各自计数：

```java
@TestFactory
Stream<DynamicTest> list_filters_by_status() {
    record Case(String name, OrderStatus status, int expectedSize) { }
    List<Case> cases = List.of(
            new Case("不传状态返回全部", null, 3),
            new Case("只取 NEW", OrderStatus.NEW, 2),
            new Case("只取 PAID", OrderStatus.PAID, 1),
            new Case("只取 CANCELLED", OrderStatus.CANCELLED, 0));
    return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
        when(orders.findAll(c.status())).thenReturn(
                Collections.nCopies(c.expectedSize(), SAMPLE));
        assertThat(service.list(c.status())).hasSize(c.expectedSize());
    }));
}
```

四个用例的名字直接取自数据表，surefire 报告里能看到 list_filters_by_status()[1] 到 [4]。以后新增一种过滤规则，往表里加一行就行，用例自动多一个。单元层实测：8 个用例，1.645 秒。

### 切片层：@WebMvcTest 与 MockMvcTester

切片层只装 Web 这一片：@WebMvcTest(OrderController.class) 装入控制器与 MVC 基础设施，业务 Service 用 @MockitoBean 换成替身。断言的主角是 MockMvcTester，断言写在 assertThat 里，状态码与 JSON 字段一条链走完：

```java
@WebMvcTest(OrderController.class)
class OrderControllerSliceTest {

    @Autowired
    MockMvcTester mockMvc;

    @MockitoBean
    OrderService orders;

    @Test
    void create_returns_201_with_body() {
        when(orders.create(eq("SK-T4-1"), any())).thenReturn(SAMPLE);

        assertThat(mockMvc.post().uri("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"SK-T4-1\",\"amount\":359.00}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("orderNo").isEqualTo("SK-T4-1");
    }

    @Test
    void get_missing_order_returns_404_problem_detail() {
        when(orders.getById(99L)).thenThrow(new OrderNotFoundException(99L));

        assertThat(mockMvc.get().uri("/api/orders/99"))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("type").isEqualTo("urn:problem-type:order-not-found");
    }
}
```

bodyJson().extractingPath(...) 直接对响应 JSON 取路径断言，与 Spring Security 测试里 jsonPath 的效果相同，但不用再写 andExpect 的嵌套。@WebMvcTest 自动装载 @RestControllerAdvice，404 分支的 ProblemDetail 结构（type、title、status、detail）在这里就能断言，不必起整个应用。

这一层我还复现了校验失败路径：orderNo 传空串、amount 传负数，@NotBlank 与 @DecimalMin 把请求挡在 400。五个用例实测 5.337 秒，其中 Spring 上下文启动 2.365 秒，近一半时间花在起上下文上，断言本身非常快。

### 端到端层：RestTestClient 起真实端口

端到端层用 @SpringBootTest(RANDOM_PORT) 起一个真实 Tomcat，RestTestClient 绑定运行中的端口发真 HTTP 请求，数据源还是内存 H2。一条用例走完建单、支付、查询、删除全链路：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiE2eTest {

    @LocalServerPort
    int port;

    RestTestClient rest;

    @BeforeEach
    void setUp() {
        if (rest == null) {
            rest = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port).build();
        }
    }

    @Test
    void full_lifecycle_create_pay_list_delete() {
        String orderNo = "SK-E2E-" + System.nanoTime();

        rest.post().uri("/api/orders")
                .body(Map.of("orderNo", orderNo, "amount", new BigDecimal("129.90")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("NEW")
                .jsonPath("$.amount").isEqualTo(129.90);

        long id = orders.findAll(OrderStatus.NEW).stream()
                .filter(o -> o.orderNo().equals(orderNo))
                .findFirst().orElseThrow().id();

        rest.post().uri("/api/orders/{id}/pay", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PAID");

        rest.delete().uri("/api/orders/{id}", id)
                .exchange()
                .expectStatus().isNoContent();
    }
}
```

RestTestClient 的断言风格是 expectStatus 与 expectBody 链式往下走，jsonPath 取字段，与 WebClient 侧的 WebTestClient 同一套手感。bindToServer 走真实网络栈，请求头、序列化、异常处理器与生产路径一致；重复单号在这层返回 409，响应体的 urn:problem-type:duplicate-order 也进了断言。四个用例实测 5.443 秒，上下文启动 1.645 秒。

![](https://static.xiongneng.me/testing-service-connection-seq-20260922050909.png)

## Testcontainers：给数据层一台真实 MySQL

前三层的数据库都是 H2 内存库，SQL 方言差异、唯一约束行为、驱动细节全被它藏住了。数据层我换成真实 MySQL：Testcontainers 在 Docker 里起一个 mysql:8.4.7 容器，@ServiceConnection 把容器的连接信息自动灌进 Spring 的数据源装配。

环境接线先交代清楚，这是本篇踩坑最多的一段，我闹笑话的起点也在这里。开发机是 Windows，装不了 Docker；局域网里有一台树莓派（192.168.1.97，Ubuntu 24.04），Docker 29.1.3 在上面跑着 MySQL、Redis、RabbitMQ。Testcontainers 官方支持 DOCKER_HOST 指向远程 Docker，我的第一反应是 ssh 方案：给本机配了 ed25519 免密公钥、把 ubuntu 用户加进 docker 组，`DOCKER_HOST=ssh://ubuntu@192.168.1.97` 跑一个最小实验，日志直接打脸：

```text
WARN o.t.d.DockerClientProviderStrategy - Unknown DOCKER_HOST scheme ssh, skipping the strategy test...
ERROR o.t.d.DockerClientProviderStrategy - EnvironmentAndSystemPropertyClientProviderStrategy:
      failed with exception IllegalArgumentException
      (Unsupported protocol scheme: ssh://ubuntu@192.168.1.97)
```

Testcontainers 2.0.5 的客户端策略列表里没有 ssh scheme，这条路在当前版本走不通，公钥白配了。我换成 TCP 方案：树莓派上给 dockerd 加一个监听地址，systemd drop-in 一份配置：

```ini
# /etc/systemd/system/docker.service.d/tcp2375.conf
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd -H fd:// -H tcp://0.0.0.0:2375 --containerd=/run/containerd/containerd.sock
```

`systemctl daemon-reload && systemctl restart docker` 之后，2375 端口开始监听，机器上已有的容器（unless-stopped 策略）五秒内自动拉回。本机 `DOCKER_HOST=tcp://192.168.1.97:2375` 再跑实验，容器在树莓派上起成功，JDBC 从 Windows 本机直连容器断言通过。要多说一句的是，2375 无认证，只适合内网。

数据层测试代码因此只有三行注解是新的：

```java
@Testcontainers
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCKER_HOST", matches = "tcp://.*")
class OrderRepositoryContainerTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.7");

    @Autowired
    OrderRepository orders;

    @Test
    void insert_and_find_back_through_real_mysql() {
        Order saved = orders.insert(new Order(null, "SK-TC-1",
                new BigDecimal("359.00"), OrderStatus.NEW, Instant.now()));

        Order loaded = orders.findById(saved.id()).orElseThrow();
        assertThat(loaded.orderNo()).isEqualTo("SK-TC-1");
        assertThat(loaded.amount()).isEqualByComparingTo("359.00");
        assertThat(loaded.status()).isEqualTo(OrderStatus.NEW);
    }
}
```

@Testcontainers 与 @Container 让 testcontainers-junit-jupiter 管容器生命周期，类里所有用例跑完容器自动销毁；@ServiceConnection（org.springframework.boot.testcontainers.service.connection）注册一个 ConnectionDetails bean，数据源自动配置读它拿 url、用户名、密码，application.yml 里那行 H2 地址被整体覆盖，全程没有 @DynamicPropertySource 手写映射。@EnabledIfEnvironmentVariable 是跳过开关：环境里没有指向 TCP 的 DOCKER_HOST 时这五个用例自动跳过，CI 没配 Docker 也不至于红灯。

测试里我顺手打印了容器元数据，下面是一次实跑的原始记录，我留了底：

```text
TC-INFO containerId=ddf36ce4a2bedf407457821c68132791bfbecf6e8f75897fffc163936287e3b7
        jdbcUrl=jdbc:mysql://192.168.1.97:32775/test mappedPort=32775
Container mysql:8.4.7 started in PT42.8315454S
Container testcontainers/ryuk:0.14.0 started in PT1.753057S
```

![](https://static.xiongneng.me/testing-container-log-20260920134051.png)

连接串是 jdbc:mysql://192.168.1.97:32775/test，这里有个细节值得掰开说：Testcontainers 探测到本机不在 Docker 宿主机上，把映射端口挂在宿主机（树莓派）的可达地址后面，32775 是随机映射端口。真正出乎我意料的是那 42.83 秒：MySQL 容器启动占了 42.83 秒，我一度以为是断言慢，拆开日志才发现大头全在等容器就绪，树莓派硬件慢是主因；镜像首次拉取那次实验测得 187 秒，镜像落库后稳定在 43 秒上下。ryuk 容器 1.75 秒启动，负责测试结束后回收资源。数据层五个用例（插查、状态更新、删除、唯一约束拦截、容器可达性）实测 49.03 秒，扣掉等容器的 43 秒，数据库断言本身 6 秒上下。把这个拆开看清楚之后我反而踏实了：数据层慢不是 MySQL 慢，是容器就绪慢，这正是它该最后跑、最少跑的原因。

## 完整案例

一轮实跑的口径先摆出来：Windows 11 本机，JDK 21，`DOCKER_HOST=tcp://192.168.1.97:2375`，mvn test 一次跑完，30 个用例全绿，BUILD SUCCESS，总耗时 54.683 秒。

![](https://static.xiongneng.me/testing-surefire-log-20260920134050.png)

surefire 对账（Tests run 列为各类用例数，Time elapsed 为类耗时）：

```text
JUnit6FeaturesTest            8   0.245s   PER_CLASS 生命周期 / 并发执行 / 动态测试
OrderServiceUnitTest          8   1.645s   单元层，Mockito 替身
OrderControllerSliceTest      5   5.337s   切片层，@WebMvcTest + MockMvcTester
OrderApiE2eTest               4   5.443s   端到端层，RestTestClient，内存 H2
OrderRepositoryContainerTest  5  49.03s    数据层，真实 MySQL（容器 42.83s）
合计                          30  54.683s（mvn test 总耗时，BUILD SUCCESS）
```

时间分布印证了分层的价值：改一行业务逻辑，单元层 1.6 秒出反馈；改一行控制器代码，切片层 5 秒出反馈；只有数据库方言与约束相关的改动才需要等那 49 秒。三层快的加起来不到 13 秒，日常开发 90% 的改动用不到最慢那层。

应用本体我也照惯例冒烟一轮：打包后以 H2 配置起在 18210，POST /api/orders 返回 201（响应体 {"id":1,"orderNo":"SK-VERIFY-1","amount":42.00,"status":"NEW",...}），同一单号再发返回 409，GET /api/orders/9999 返回 404，应用日志 ERROR 0 行、WARN 0 行。

![](https://static.xiongneng.me/testing-api-panels-20260920134109.png)

## 避坑指南

**坑一，Testcontainers 2.x 砍掉了 ssh scheme。** 我的免密公钥和 docker 组配置全白做了，DOCKER_HOST=ssh:// 在 1.x 可用，2.0.5 的策略列表里已经没有它，实测日志是 Unknown DOCKER_HOST scheme ssh。远程 Docker 走 TCP（dockerd 加 -H tcp://0.0.0.0:2375）或装 Docker Desktop，ssh 地址写进 DOCKER_HOST 只会得到 Unsupported protocol scheme。

**坑二，2375 端口无认证。** 开了这个口子，内网里任何人都能在宿主机上起容器、挂载宿主文件系统。我内网自用可以接受，公网机器绝不能开；能上 TLS 与证书认证的 2376 是正经做法。

**坑三，映射端口挂在 Docker 宿主机上。** getJdbcUrl() 返回的是宿主机可达地址加随机端口（实测 jdbc:mysql://192.168.1.97:32775/test），断言用的 JDBC 客户端必须能直连这个地址；本机与 Docker 宿主机之间有防火墙时，先放通宿主机的 ephemeral 端口段。

**坑四，2.x 的 MySQLContainer 不是泛型类。** 我按 1.x 的肌肉记忆写了 `MySQLContainer<?>`，直接编译错误「类型不带有参数」。声明写成 `static MySQLContainer mysql = new MySQLContainer("mysql:8.4.7")` 就好。

**坑五，JdbcClient 执行 INSERT 不能用 query()。** query() 底层走 executeQuery，MySQL 驱动抛 SQLException（Statement.executeQuery() cannot issue statements that do not produce result sets），H2 也会报 Method is only allowed for a query。取自增主键用 update(KeyHolder)，配合 GeneratedKeyHolder 拿回 id。

**坑六，环境变量双下划线会被宽松绑定读进去。** 我本机环境里注入过 SERVER__PORT=52438（双下划线），Boot 把它解析成 server.port，应用起在了 52438 而不是 yml 里写的 18210，这个我排查了好一阵才定位到。跑应用前检查环境变量，SERVER_PORT 与 SERVER__PORT 都要留心。

**坑七，切片测试忘了替身直接启动失败。** @WebMvcTest 只装配 Web 层，Controller 依赖的 OrderService 没有真实 bean，也没有 @MockitoBean 替身时，上下文启动直接 NoSuchBeanDefinitionException。切片注解圈多小，替身就得补多齐。

**坑八，首次跑数据层先预热镜像。** mysql:8.4.7 首次拉取实测 187 秒，之后稳定在 43 秒。CI 上第一次跑测试前先单独 pull 一次，或者用 Testcontainers 的镜像预热机制，别把拉镜像的时间算进测试超时。

**坑九，并行开关先给默认值再放开。** junit-platform.properties 里 mode.default 必须是 same_thread：类内并发会让共享状态（同一个测试方法里的容器字段、@Transactional 回滚）互踩。我只放开了 classes.concurrent，实测 30 个用例无一互扰。

## 小结

选型建议就一条主线：按改动的落点选层。逻辑规则放单元层，不起 Spring 上下文，反馈最快；接口契约放切片层，断言写起来最舒服；跨层链路放端到端层；方言与约束相关的才进数据层。工具面不用纠结，JUnit 6 与五个测试依赖全部交给 BOM 托管，Testcontainers 配 @ServiceConnection 三行注解换一台真库，这笔交换稳稳的赚。ssh scheme 在 2.x 走不通这件事记住就行，远程 Docker 的正路是 TCP 加防火墙边界。

真正没解决的事有两件。一是 2375 无认证，TLS 2376 我还没配，这台树莓派目前只敢养在内网；二是数据层等容器就绪的那几十秒还压不下去，树莓派的硬件是天花板，等我把 Testcontainers 的容器复用模式试完，再决定要不要给数据层单开一条常驻容器的路子。

## 参考链接

- [JUnit 6 用户指南](https://docs.junit.org/current/user-guide/)：@TestFactory、@TestInstance、并行执行开关的权威说明
- [Testcontainers 官方文档](https://java.testcontainers.org/)：容器生命周期、ryuk 与 DOCKER_HOST 配置
- [Testcontainers MySQL 模块](https://java.testcontainers.org/modules/databases/mysql/)：MySQLContainer 用法与 JDBC URL 规则
- [Spring Boot Testing 参考文档](https://docs.spring.io/spring-boot/reference/testing/)：切片测试、@ServiceConnection 与 Testcontainers 集成的官方说明
- [Spring Framework Testing](https://docs.spring.io/spring-framework/reference/testing/)：MockMvcTester 与 RestTestClient 的 API 说明
- [Maven Surefire Plugin](https://maven.apache.org/surefire/maven-surefire-plugin/)：JUnit Platform Provider 与测试报告口径
- [Spring Boot Testcontainers 模块（Maven Central）](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-testcontainers/4.1.1/)：本文版本结论的制品实证来源
