# springboot4-testing

Spring Boot 4 教程第 21 篇：测试体系（JUnit 6 与 Testcontainers）。

给系列一贯的订单接口搭一套成体系的测试：单元层（Mockito）→ 切片层（@WebMvcTest + MockMvcTester）→ 端到端层（@SpringBootTest + RestTestClient）→ 数据层（Testcontainers 起真实 MySQL，@ServiceConnection 自动接线）。

- 基线：JDK 21、Spring Boot 4.1.1、Maven 3.9
- JUnit 6.0.3（Boot 官方 BOM 经 junit-bom 6.0.3 管理，工程不写版本号）
- Testcontainers 2.0.5（BOM 管理，MySQL 模块坐标 org.testcontainers:testcontainers-mysql）

## 运行

```bash
mvn spring-boot:run
```

应用默认端口 18210（`server.port: ${SERVER_PORT:18210}`）。本地运行与端到端测试用内存 H2（MySQL 兼容模式），无需任何外部数据库。

> 注意：如果你的运行环境注入了 `SERVER__PORT` 环境变量（双下划线），Boot 的宽松绑定会把它读成 server.port。需要显式 `SERVER_PORT=18210` 覆盖或 unset `SERVER__PORT`。

## 测试

数据层测试需要 Docker。本工程实测环境：Windows 本机没有 Docker，Testcontainers 远程连树莓派（192.168.1.97）上的 Docker 29.1.3。

```bash
# 树莓派侧一次性配置（sudo 免密）：
#   /etc/systemd/system/docker.service.d/tcp2375.conf
#   [Service]
#   ExecStart=
#   ExecStart=/usr/bin/dockerd -H fd:// -H tcp://0.0.0.0:2375 --containerd=/run/containerd/containerd.sock
#   systemctl daemon-reload && systemctl restart docker

# 本机侧跑测试（2375 无认证，仅限内网）：
export DOCKER_HOST=tcp://192.168.1.97:2375
mvn test
```

不设 DOCKER_HOST 时数据层测试类自动跳过（@EnabledIfEnvironmentVariable），其余 25 个用例照常全绿。

Testcontainers 2.0.5 不支持 `DOCKER_HOST=ssh://` scheme（日志实证 Unknown DOCKER_HOST scheme ssh），ssh 方案在当前版本走不通，TCP 2375 是实测可行路线。

## 分层测试矩阵

| 层 | 测试类 | 用例数 | 实测耗时 | 依赖 |
|---|---|---|---|---|
| JUnit 6 特性 | JUnit6FeaturesTest | 8 | 0.245s | 无 Spring 上下文 |
| 单元层 | OrderServiceUnitTest | 8 | 1.645s | Mockito 模拟 OrderRepository |
| 切片层 | OrderControllerSliceTest | 5 | 5.337s | @WebMvcTest + MockMvcTester + @MockitoBean |
| 端到端层 | OrderApiE2eTest | 4 | 5.443s | RANDOM_PORT + RestTestClient，内存 H2 |
| 数据层 | OrderRepositoryContainerTest | 5 | 49.03s | MySQLContainer 8.4.7 + @ServiceConnection |

合计 30 个用例，`BUILD SUCCESS`，mvn 总耗时 54.683s。数据层容器启动 42.83s（树莓派硬件 + 镜像已缓存，首次拉取另计约 187s），容器 JDBC URL 实测 `jdbc:mysql://192.168.1.97:32775/test`（@ServiceConnection 自动探测宿主可达地址与映射端口）。

## 各层说明

- **JUnit 6 特性**：@TestInstance(PER_CLASS) 非 static @BeforeAll、@RepeatedTest 并发执行（junit-platform.properties 开启类间并发）、@TestFactory 动态测试。
- **单元层**：MockitoExtension + @Mock/@InjectMocks，不碰 Spring 上下文，毫秒级；@TestFactory 用状态矩阵生成 4 个动态用例。
- **切片层**：@WebMvcTest(OrderController) 只装 Web 层，注入 MockMvcTester（org.springframework.test.web.servlet.assertj），断言写法 assertThat(...).hasStatusOk().bodyJson().extractingPath(...)；@MockitoBean 替换 OrderService；@WebMvcTest 自动装载 @RestControllerAdvice。
- **端到端层**：@SpringBootTest(RANDOM_PORT) 起真实 Tomcat，RestTestClient（org.springframework.test.web.servlet.client）bindToServer 直连运行中端口，expectStatus/expectBody/jsonPath 链式断言。
- **数据层**：@Testcontainers + @Container + @ServiceConnection 三注解；application.yml 里的 H2 连接被 ConnectionDetails 覆盖为容器 MySQL；唯一约束、状态更新、删除等断言全部打在真实 MySQL 上。

## 实测结论（同一次运行）

- 30 个用例全绿，应用日志 ERROR 0 行
- 单元层 1.645s、切片层 5.337s、端到端层 5.443s、数据层 49.03s（含容器启动 42.83s）
- ryuk 容器（testcontainers/ryuk:0.14.0）启动 1.75s，负责测试结束后清理容器
- 接口冒烟：POST /api/orders 201、重复单号 409、不存在订单 404

## 目录结构

```text
src/main/java/com/xncoding/testing/
  TestingApplication.java
  order/    Order、OrderStatus、OrderRepository(JdbcClient)、OrderService、OrderController、DTO 与异常
  config/   GlobalExceptionHandler（ProblemDetail）
src/test/java/com/xncoding/testing/
  junit6/   JUnit6FeaturesTest
  unit/     OrderServiceUnitTest
  slice/    OrderControllerSliceTest
  e2e/      OrderApiE2eTest
  data/     OrderRepositoryContainerTest
```
