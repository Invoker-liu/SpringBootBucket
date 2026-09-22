# springboot4-aop：使用 AOP

Spring Boot 4.1.1 + JDK 21。本工程是《SpringBoot4系列16 - 使用AOP》的配套源码，
延续订单业务：接口耗时统计、操作审计日志落 MySQL、幂等防重打回重复提交。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 4.1.1 | spring-boot-starter-parent |
| AOP starter | spring-boot-starter-aspectj | = starter + spring-aop 7.0.9 + aspectjweaver 1.9.25.1（`spring-boot-starter-aop` 在 4.1.1 不存在） |
| 数据访问 | spring-boot-starter-jdbc | 审计落库用 JdbcTemplate，不引 ORM |
| MySQL | 8.4 | 192.168.1.97:3306，库 `springboot4_aop` |

## 四个切面与 @Order

| 顺序 | 切面 | advice | 切点 | 职责 |
|---|---|---|---|---|
| @Order(1) | IdempotentAspect | @Around | @annotation(@Idempotent) | 请求指纹防重，重复提交抛异常打回 409 |
| @Order(2) | MetricsAspect | @Around | @within(@RestController) | 接口耗时统计，异常原样上抛 |
| @Order(3) | TraceAspect | @Before / @AfterReturning / @AfterThrowing / @After | execution(service 包) | service 层进出场、返回值、异常跟踪 |
| @Order(4) | AuditAspect | @Around | @annotation(@OperationLog) | 审计落 MySQL，成败都落行 |

切面链的实测顺序（创建订单一次请求）：`AOP_IDEM enter -> AOP_METRICS enter -> AOP_AUDIT enter
-> AOP_TRACE enter -> 业务 -> AOP_TRACE return/exit -> AOP_AUDIT done -> AOP_METRICS exit -> AOP_IDEM exit`。
Trace 织入在 service 层代理上，位于控制器层审计切面之内，所以审计先于跟踪退场。

## 运行

前置：MySQL 可达（`spring.datasource` 默认指向 192.168.1.97:3306，可用环境变量
`DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` 覆盖）。建库可跑
`bash .workbuddy/reset-aop-db.sh`，不跑也行：应用启动时 `spring.sql.init.mode=always`
会执行 `sql/schema.sql`（建库需手动或用脚本）。

```bash
bash .workbuddy/tools/mvn.sh -f springboot4-aop/pom.xml spring-boot:run
# HTTP 端口 18160
```

## 测试

```bash
bash .workbuddy/tools/mvn.sh -f springboot4-aop/pom.xml test
```

9 个用例，全部连真实 MySQL 审计库：

- `AspectOrderTest`：一次请求的切面日志顺序（OutputCapture 断言 containsSubsequence），成功与异常两条路径
- `IdempotentWebTest`：同一请求体第二次 POST 打回 409 problem+json；支付接口同规则
- `AuditAspectDbTest`：成功落 1 行 status=OK；业务异常落 status=ERROR 且 error=异常类名；被幂等打回的请求不落审计行
- `AroundPitfallTest`：@Around 吞异常返回 null、忘调 proceed 的两个坑，用 AspectJProxyFactory 最小上下文实测

## 接口清单

| 方法 | 路径 | 注解 | 说明 |
|---|---|---|---|
| POST | /api/orders | @OperationLog + @Idempotent | 创建订单，重复请求体 60 秒内打回 409 |
| GET | /api/orders/{orderNo} | @OperationLog | 查订单，不存在返回 404 |
| POST | /api/orders/{orderNo}/pay | @OperationLog + @Idempotent | 支付，重复支付打回 409 |
| POST | /api/orders/{orderNo}/pack | @OperationLog | 打包出库，模拟 120ms 工作量，供耗时统计观察 |
| POST | /api/ops/audit-clear | 无 | 清空审计表（观测动作不写审计） |
| GET | /api/ops/audit?limit=10 | 无 | 最近审计行 |
| GET | /api/ops/stats | 无 | 审计行数 / ERROR 行数 / 库存 / 订单数 |

错误响应统一 RFC 9457（`application/problem+json`）：幂等拒绝 409
`urn:problem-type:duplicate-request`、订单不存在 404、库存不足 422。

## 一轮实测结论（verify-aop.sh，同一次运行）

- 耗时统计：打包出库业务 sleep 120ms，审计切面记 125ms，@Around 记 136ms，HTTP 全程 140ms，三层耗时差就是切面与框架开销
- 审计对账：7 个场景落 7 行（5 行 OK + 2 行 ERROR），2 次被幂等打回的请求 0 行，行数恒等式成立
- 幂等防重：创建订单与支付两个接口的第二次同指纹请求均 409，`windowSeconds=60` 在响应体里
- 切面顺序：enter 序列 IDEM -> METRICS -> AUDIT -> TRACE，退场严格逆序
- 应用日志 ERROR 0 行
