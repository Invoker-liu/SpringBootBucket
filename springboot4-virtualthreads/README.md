# springboot4-virtualthreads

Spring Boot 4 教程第 27 篇配套工程：Java 21 虚拟线程与 `spring.threads.virtual.enabled` 开关，全部在 Boot 4.1.1 上实测。

## 技术栈

- Spring Boot 4.1.1（parent 4.1.1，Java 21，Spring Framework 7.0.9，Tomcat 11.0.24）
- `spring.threads.virtual.enabled=true/false` 两档切换：Tomcat 请求处理线程在 `VirtualThreadExecutor`（前缀 `tomcat-handler-`）与传统 `http-nio-*-exec-N` 线程池之间切换
- spring-boot-starter-webmvc（Boot 4 中 spring-boot-starter-web 已弃用）
- 端口 18270（本系列统一约定，环境变量 SERVER_PORT 可覆盖）

## 业务场景

订单域「调用下游汇总」接口，IO 密集型：每个请求内阻塞等待 3 个下游（库存 / 定价 / 物流），下游用工程内 `Thread.sleep` 可控延迟模拟，sleep 与 socket 阻塞在虚拟线程上的卸载行为一致：

| 接口 | 场景 |
|---|---|
| GET /api/orders/{id}/summary?delayMs= | 请求线程串行调 3 个下游，总耗时约 3 × delayMs，响应携带请求线程与下游线程名 |
| GET /api/orders/{id}/summary-parallel?delayMs= | `Executors.newVirtualThreadPerTaskExecutor` 把 3 个下游扇出到 3 个虚拟线程并行，总耗时约 max(delayMs) |
| POST /api/orders/{id}/audit | `Thread.ofVirtual().name("audit-<id>")` 起虚拟线程异步写审计，程序式创建不受开关约束 |
| GET /api/threads/status | 返回开关取值与当前请求线程的 `Thread.toString()`，供测试与脚本断言线程档位 |

## 构建与运行

```bash
# 构建（跳过测试）
mvn package -DskipTests

# 平台线程档（默认）
java -jar target/springboot4-virtualthreads-1.0.0.jar

# 虚拟线程档
java -jar target/springboot4-virtualthreads-1.0.0.jar --spring.threads.virtual.enabled=true
```

## 验证

```bash
# 单元/端到端测试（9 个用例：两档线程名断言、串行同线程、并行扇出、虚拟线程命名、请求独立线程）
mvn test

# 验证脚本：两档各「冷启 + 压测 + 关服务」，100 并发 × 400 请求 × 3×200ms 下游延迟，
# 输出吞吐 req/s、p50/p99、线程名样本，落盘 .workbuddy/virtualthreads/取值单.txt
bash .workbuddy/verify-virtualthreads.sh
```

实测参考值（2026-09-20 轮，同机同轮先后执行）：平台线程档 32.9 req/s、客户端 p99 3088.2ms、请求线程去重 20 个（`http-nio-18270-exec-N`）；虚拟线程档 157.4 req/s、客户端 p99 652.9ms、请求线程去重 400 个（`VirtualThread[...tomcat-handler-N]`），吞吐 4.78 倍，应用侧耗时中位两档均约 602ms，应用日志 ERROR 0 行。

## 关键结论

- `spring.threads.virtual.enabled` 是 Boot 4.1.1 里唯一的相关属性，属性元数据中不存在 `spring.threads.virtual.name-prefix`，线程前缀由各组件硬编码
- 开关作用面（字节码 grep 实证）：Tomcat 请求处理、applicationTaskExecutor（@Async 默认执行器）、任务调度器（@Scheduled）、RabbitMQ 监听器容器工厂、Redis 连接配置、OTLP 指标导出
- 开启后所有线程池配置属性失效（虚拟线程调度在 JVM 级平台线程池上）；虚拟线程均为守护线程，官方建议配 `spring.main.keep-alive=true`
- JDK 21 上 `synchronized` 块内阻塞仍会 pin 载体线程，JDK 24（JEP 491）才解除；诊断用 JFR 事件 `jdk.VirtualThreadPinned`

## 作者

Xiong Neng
