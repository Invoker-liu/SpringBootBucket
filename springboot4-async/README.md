# springboot4-async：异步任务与线程池

Spring Boot 4.1.1 + JDK 21 + Maven 3.9。场景是订单业务：下单后要并行做三件慢事（发短信通知 600ms、积分入账 700ms、报表聚合 800ms），同步串行做响应 2.1 秒，异步化后毫秒返回。

## 运行

默认连树莓派 MySQL（192.168.1.97:3306，库名 springboot4_async，`createDatabaseIfNotExist=true` 自动建库），应用端口 18120：

```bash
mvn spring-boot:run -f springboot4-async/pom.xml
```

需要改端口或库时用环境变量：`SERVER_PORT` / `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`。

## 测试

17 个用例全连真实 MySQL，覆盖配置事实、接口行为、异步并行、异常分流、拒绝策略：

```bash
mvn test -f springboot4-async/pom.xml
```

- `AsyncConfigTest`（5）：属性值经 `ThreadPoolTaskExecutorBuilder` 流进自定义池、有界池参数、Boot 默认执行器退让、`AsyncConfigurer` 接线、`@EnableAsync` 在位
- `OrderApiIntegrationTest`（4）：异步下单 < 1.5 秒、同步基线 >= 2 秒、校验 400、查询 404
- `AsyncFlowIntegrationTest`（4）：三任务并行在不同 biz 线程、台账聚合、this 自调用失效、ThreadLocal 断层（Awaitility 轮询，不打时间的主意）
- `ExceptionRoutingTest`（3）：void 异常进 `AsyncUncaughtExceptionHandler`、CompletableFuture 异常待在 future 里、处理器契约不外抛
- `RejectionPolicyTest`（1）：有界池 core 2 / max 4 / queue 2 灌 10 个任务，确定性接受 6 拒绝 4

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | 异步下单：落库后把三件慢事并行投出去，返回 `{orderNo, taskId, mode, elapsedMillis}` |
| POST | `/api/orders/sync` | 同步基线：同样的三件慢事串行做完才返回（约 2.1 秒） |
| GET | `/api/orders/{orderNo}` | 订单查询，不存在返回 404 problem+json |
| GET | `/api/tasks/{taskId}` | 任务台账：聚合状态 + 每件慢事的线程名/耗时/异常，future 异常在 `futureException` 字段 |
| POST | `/api/probe/self-invocation` | this 自调用 vs 代理调用，线程名直接暴露谁走了代理 |
| POST | `/api/probe/void-exception` | 提交一个会抛异常的 void 任务，进 Uncaught 处理器（计数 + 台账标 FAILED） |
| POST | `/api/probe/future-exception` | 提交一个会抛异常的 CompletableFuture 任务，异常待在 future 里 |
| POST | `/api/probe/thread-local` | HTTP 线程写 traceId，异步线程读出来是 null |
| POST | `/api/probe/rejection?tasks=10` | 向有界池灌 N 个慢任务，返回接受/拒绝计数 |
| GET | `/api/probe/counters` | 未捕获异常命中数、拒绝次数 |
| GET | `/api/probe/stats` | 两个池的实时状态（core/max/active/queue/completed）+ 计数器 |

请求体示例：

```bash
curl -s -X POST http://localhost:18120/api/orders \
     -H "Content-Type: application/json" \
     -d '{"orderNo":"SO80002","amount":"299.00"}'
```

## 实测结论（一次运行的取值单）

- 同步下单 `elapsedMillis = 2155`，异步下单 `elapsedMillis = 23`
- 三件慢事 sms/points/report 耗时 605/713/807ms，分别跑在 `biz-1` / `biz-2` / `biz-3` 三条线程
- void 方法抛 `IllegalStateException` → `uncaughtHandled` 计数 +1，台账 FAILED，应用日志 ERROR 0 行、WARN 1 行
- CompletableFuture 方法抛异常 → `get()` 处拿到 `ExecutionException`，不经过 Uncaught 处理器
- this 自调用 `selfCallAsync=false`（方法跑在 HTTP 线程），代理调用跑在 `biz-2`
- ThreadLocal：HTTP 线程 `trace-0b79adcd`，异步线程 `null`
- 有界池灌 10 个任务：接受 6、拒绝 4，`rejectionCounter = 4`
- `spring.task.execution.*` 的 core-size=4 / thread-name-prefix=biz- 经 builder 流进自定义池，测试与台账线程名双向印证

## 线程池的两条路

- **属性路**：`application.yml` 的 `spring.task.execution.*`（core-size 4 / max-size 8 / queue 100 / 前缀 biz-）喂给 `ThreadPoolTaskExecutorBuilder`，`@Bean bizExecutor` 只调 `builder.build()`。
- **手工路**：`boundedExecutor` 手工 new，core 2 / max 4 / queue 2，拒绝策略自定义（先计数再抛 `RejectedExecutionException`）。
- 自定义 `AsyncConfigurer` 把 @Async 默认执行器指向 `bizExecutor`，void 异常接到自己的 `UncaughtCounter`；声明它的那一刻，Boot 自动装配的 `applicationTaskExecutorAsyncConfigurer` 退位。
