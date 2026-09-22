---
title: SpringBoot4系列27 - 使用虚拟线程提升吞吐
slug: sb4-virtualthreads
date: 2026-09-20 22:30:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, 虚拟线程, VirtualThreads, Tomcat ]
draft: false
---

先摆一下我遇到的问题。订单详情页有一个聚合接口：请求进来后要串行调库存、定价、物流三个下游，每次调用 200ms，接口总耗时 600ms。大促峰值要撑 3000 QPS，按排队论算容量，需要的并发处理线程数是 3000 × 0.6s = 1800 个。Tomcat 的请求线程池默认上限 200，吞吐天花板是 200 / 0.6s ≈ 333 req/s，距离目标差 5 倍。把线程池调到 2000 也能算出差不多的容量，但每个平台线程预分配 1MB 栈内存，2000 个线程就是 2GB 的固定开销，加上上下文切换和调度成本，这条路我在评审里见过太多回，基本都走到头了。

瓶颈不在 CPU：线程 99% 的时间在等下游返回，CPU 利用率不到 10%。等待型负载恰好是 Java 21 虚拟线程的目标场景。虚拟线程是 JVM 管理的轻量线程，阻塞时卸载载体线程，创建成本接近一个普通对象。

另一条路是横向扩容。3000 QPS 按 333 req/s 的单实例上限要 9 个副本，副本数翻倍的话数据库连接池、下游限流阈值都要跟着调，多付的是九份的常驻内存与运维成本。虚拟线程把「一个请求占一个 OS 线程 600ms」改成「一个请求占一个廉价虚拟线程 600ms」，单实例容量逼近并发上限，这是我看中它的地方。

Spring Boot 4.1 把它做成一个属性开关。我在 Boot 4.1.1（Spring Framework 7.0.9、JDK 21.0.10）上用同一套代码在两档配置下实测线程模型和吞吐，所有数字都是同一份验证脚本一口气测出来的，每一行原始记录我都留了底；配套工程 springboot4-virtualthreads 是订单域「调用下游汇总」接口，端口 18270。

![](https://static.xiongneng.me/virtualthreads-switch-surface-20260922070210.png)

## 开关与作用面

工程依赖照旧只有 webmvc 一个 starter，虚拟线程能力在 JDK 与 Boot 里都是内置的，不需要引第三方库：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

Boot 4 中 `spring-boot-starter-web` 已弃用，本系列统一用 `spring-boot-starter-webmvc`；内嵌 Tomcat 由 `spring-boot-tomcat` 模块自动配置，后文的所有取证都发生在这个模块与 spring-boot-autoconfigure 里。

开启虚拟线程只需要一个属性：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

这个属性在 Boot 4.1.1 的属性元数据里是唯一入口，类型 `Boolean`，默认 `false`。网上流传的 `spring.threads.virtual.name-prefix` 在 4.1.1 的属性元数据里不存在，我第一回就把这个流传的属性抄了上去，启动日志里一条未知属性警告给我当头一棒；线程前缀由各组件自己硬编码。

开关背后的判定机制是条件注解。Boot 定义了 `Threading` 枚举（`PLATFORM` 与 `VIRTUAL` 两个值，位于 `org.springframework.boot.thread` 包）和 `@ConditionalOnThreading` 注解，自动配置类用它声明「这段配置只在虚拟线程启用时生效」。属性写入环境后，`OnThreadingCondition` 在评估时读取它决定条件走向，命中 `VIRTUAL` 分支的配置才会注册。这里还有一个 Boot 4 的结构性变化值得交代：条件使用点分散在 tomcat、amqp、data-redis 等多个模块里，Boot 4 按技术栈拆分了自动配置模块。我第一轮扫描就栽在这里：只 grep 了 autoconfigure 一个 jar，数出来的使用点少了一半，全量解包扫描才补齐。

把本机仓库里 Boot 4.1.1 全部模块的 jar 解包扫描，`@ConditionalOnThreading` 的使用点覆盖六个位置：

| 模块 | 使用点 | 影响的组件 |
|---|---|---|
| spring-boot-tomcat | `TomcatWebServerConfiguration` | Tomcat 请求处理线程 |
| spring-boot-autoconfigure | `SimpleAsyncTaskExecutorBuilderConfiguration` | applicationTaskExecutor（`@Async` 默认执行器） |
| spring-boot-autoconfigure | `SimpleAsyncTaskSchedulerBuilderConfiguration` | 任务调度器（`@Scheduled`） |
| spring-boot-amqp | `RabbitAnnotationDrivenConfiguration` | RabbitMQ 监听器容器工厂 |
| spring-boot-data-redis | `LettuceConnectionConfiguration` 等 | Redis 连接配置 |
| spring-boot-micrometer-metrics | `JvmMetricsAutoConfiguration` 等 | 虚拟线程 JVM 指标、OTLP 导出 |

Tomcat 的请求处理有一个经典三层结构：Acceptor 线程负责接收连接，Poller 线程负责监听 socket 可读事件，事件就绪后把请求交给执行器（Executor）里的工作线程跑完整个 Servlet 调用。平时说的「Tomcat 线程池」就是最后这一层，`server.tomcat.threads.max` 约束的也是它。600ms 的下游等待发生在这里：工作线程拿到请求、调下游、阻塞 600ms、写响应、回池，等待全程占着池名额。理解了这一层就明白虚拟线程的改造点为什么选在执行器上：把「池里的平台线程」换成「按需新建的虚拟线程」，Acceptor 与 Poller 保持不动。

Web 层的作用点可以直接看字节码。取证不用下载源码，本机 Maven 仓库里就有现成的 jar：

```bash
# 列出 jar 内虚拟线程相关的类，确认作用点的落点
unzip -l spring-boot-tomcat-4.1.1.jar | grep -i virtual

# 反汇编定制器，看它对 Tomcat 执行器做了什么
javap -c -p org.springframework.boot.tomcat.autoconfigure.TomcatVirtualThreadsWebServerFactoryCustomizer
```

第一条命令定位到 `TomcatVirtualThreadsWebServerFactoryCustomizer`，第二条看它的方法体。javap 反汇编 `customize` 方法，对 Tomcat 的 `ProtocolHandler` 设置了这样一个执行器：

```java
private static void lambda$customize$0(ProtocolHandler handler) {
    handler.setExecutor(new org.apache.tomcat.util.threads.VirtualThreadExecutor("tomcat-handler-"));
}
```

Tomcat 11 自带的 `VirtualThreadExecutor` 把每个请求分派到一个新建的虚拟线程，前缀固定为 `tomcat-handler-`。这就是我后文压测里看到的线程名的来源。

开关打开后有一类配置会静默失效：所有配置线程池的属性。官方文档的表述是「properties which configure thread pools don't have an effect anymore」，因为虚拟线程统一调度在 JVM 级的载体线程池上，没有专用线程池可配。受影响的包括 `server.tomcat.threads.max`、`server.tomcat.threads.min-spare`、`spring.task.execution.pool.*`、`spring.task.scheduling.pool.*`，属性元数据里 `spring.task.execution.pool.keep-alive` 的描述原文就带着「Doesn't have an effect if virtual threads are enabled」。旧配置留着不报错，但读配置的人会以为线程池还在按参数工作，最容易闹笑话。

配套工程的完整配置很短，`application.yml` 全文如下：

```yaml
server:
  port: ${SERVER_PORT:18270}
  tomcat:
    threads:
      max: 20
spring:
  application:
    name: springboot4-virtualthreads
  main:
    keep-alive: true
logging:
  level:
    root: INFO
```

`server.port` 让环境变量优先，验证脚本接管端口时不用改配置；`keep-alive: true` 按官方建议常开，对平台线程档没有副作用；`tomcat.threads.max: 20` 只在平台档生效，下一节的对照实验靠它撑起差异。

另一个官方提示的副作用：虚拟线程都是守护线程。开启后 `@Scheduled` 的调度器线程也变成虚拟线程，JVM 里全是守护线程时会直接退出，定时任务应用可能启动完就挂。官方建议配 `spring.main.keep-alive=true`，Boot 会用非守护线程维持 JVM 存活。版本前置是 Java 21，官方强烈推荐 Java 24，原因在后文 pinned 一节展开；JDK 21 上运行 Boot 4.1 完全可用，本篇实测就在 21.0.10 上完成。

## 线程模型变化实测

配套工程把线程名直接暴露出来。`GET /api/threads/status` 返回开关取值和当前请求线程的 `Thread.toString()`，`GET /api/orders/{id}/summary` 串行调 3 个下游（工程内用 `Thread.sleep` 模拟可控延迟的阻塞调用，sleep 与 socket 读在虚拟线程上的卸载行为一致），响应里带请求线程和每个下游的执行线程。为了让两档差异可测，`application.yml` 把平台档的请求池上限压到 20：

```yaml
server:
  tomcat:
    threads:
      max: 20
```

虚拟线程档下这一行自动失效，正好构成同一份配置、两种线程模型的对照组。

模拟下游的服务只有一个方法，工程内它是这样实现的：

```java
@Service
public class DownstreamSimulator {
    public DownstreamResult call(String downstream, long delayMs) {
        long start = System.nanoTime();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟调用被中断", e);
        }
        long costMs = (System.nanoTime() - start) / 1_000_000;
        return new DownstreamResult(downstream, costMs, Thread.currentThread().toString());
    }
}
```

`Thread.sleep` 在虚拟线程上的行为与 socket 阻塞读一致：阻塞期间虚拟线程卸载载体、载体回去调度别的虚拟线程，醒来后可能挂到另一个 worker 上继续跑。返回值里的 `Thread.toString()` 让线程身份随响应透出，后文所有线程名证据都取自这里。工程刻意不发真实 HTTP，省掉第二台「下游服务」的部署；在请求线程里阻塞等待这一点上，sleep 与真实 RPC 的线程模型消耗相同。

控制器串行版的主体就三行调用：

```java
@GetMapping("/api/orders/{id}/summary")
public OrderSummary summary(@PathVariable String id,
                            @RequestParam(name = "delayMs", defaultValue = "200") long delayMs) {
    downstreams.add(simulator.call("inventory", delayMs));
    downstreams.add(simulator.call("pricing", delayMs));
    downstreams.add(simulator.call("logistics", delayMs));
    return new OrderSummary(id, Thread.currentThread().toString(), downstreams, costMs);
}
```

三个下游依次阻塞调用，请求线程从头占到尾，`requestThread` 字段记录的是 `Thread.currentThread()` 在入口时刻的 `toString()`。

平台线程档（`enabled=false`）的返回：

```json
{"virtualEnabled":"false",
 "requestThread":"Thread[#53,http-nio-18270-exec-1,5,main]"}
```

`http-nio-18270-exec-1` 是 Tomcat 传统线程池的命名。串行接口在这个档位下连续打三个下游，响应里三个下游线程与请求线程同名同号（`Thread[#53,...]`），全部工作压在一个池线程上，`costMs` 602。虚拟线程档（`enabled=true`）的返回：

```json
{"virtualEnabled":"true",
 "requestThread":"VirtualThread[#57,tomcat-handler-0]/runnable@ForkJoinPool-1-worker-1"}
```

`VirtualThread[#57,tomcat-handler-0]` 是请求虚拟线程，名字前缀就是反汇编里那个 `tomcat-handler-`；`@` 后面的 `ForkJoinPool-1-worker-1` 是它挂载的载体平台线程。同一个请求，两种档位下线程的身份、命名、数量级完全不同。两档的线程身份可以并排读：

| 字段 | 平台线程档 | 虚拟线程档 |
|---|---|---|
| 对象类型 | `Thread` | `VirtualThread` |
| 线程名 | `http-nio-18270-exec-1` | `tomcat-handler-0` |
| 线程编号 | `#53`，进程内递增 | `#57`，创建即递增不复用 |
| 挂载载体 | 自身即 OS 线程 | `ForkJoinPool-1-worker-1` |
| 生命周期 | 复用，跑完请求回池 | 请求结束即丢弃 |

串行接口在虚拟线程档下连续打三个下游，响应里三个下游线程与请求线程同名，三个调用都在同一个虚拟线程上完成，成本 602ms。挺有意思的是虚拟线程的名字：随请求递增不复用，我压测 400 个请求拿到 400 个不同的 `tomcat-handler-N`，这也是后文判断「池上限已失效」的铁证。两档的完整执行时序如图：

![](https://static.xiongneng.me/virtualthreads-thread-model-20260922070214.png)

开关只管 Web 层还不够，业务代码里经常需要程序式并发。`summary-parallel` 接口用 Java 21 的 API 把三个下游扇出到三个虚拟线程：

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<DownstreamResult>> futures = names.stream()
            .map(name -> executor.submit(() -> simulator.call(name, delayMs)))
            .toList();
    for (Future<DownstreamResult> future : futures) {
        downstreams.add(future.get());
    }
}
```

`newVirtualThreadPerTaskExecutor` 每提交一个任务就新建一个虚拟线程，try-with-resources 等全部任务结束后关闭。实测 `delayMs=200` 时串行接口 costMs 602、并行接口 costMs 202，下游线程名是 `VirtualThread[#63]`、`VirtualThread[#64]`、`VirtualThread[#65]` 三个独立虚拟线程。这个 API 不受开关约束，平台线程档下同样产出虚拟线程，测试类里有对应断言。

后台任务示例用另一个 API。`POST /api/orders/{id}/audit` 接口起一个命名虚拟线程异步写审计：

```java
Thread worker = Thread.ofVirtual().name("audit-" + id).start(() -> {
    try {
        Thread.sleep(50);
    } catch (InterruptedException ignored) {
    }
});
worker.join();
```

实测返回 `VirtualThread[#69,audit-o-1]/terminated`，`Thread.ofVirtual().name()` 的命名生效。`@Async` 与虚拟线程的关系在第 12 篇展开过：开关打开后 applicationTaskExecutor 由 `SimpleAsyncTaskExecutorBuilder` 以 `virtualThreads(true)` 构建，`@Async` 方法自动跑在虚拟线程上，不需要改任何注解。并行接口的完整交互时序：

![](https://static.xiongneng.me/virtualthreads-parallel-fanout-20260922070217.png)

开关作用面里还有一处面向监控的增量：micrometer 模块的 `JvmMetricsAutoConfiguration` 在虚拟线程档下会激活 `VirtualThreadMetricsConfiguration`，暴露虚拟线程的 JVM 指标（创建数、活动数、pin 次数等）。第 24 篇接好的监控体系不用改配置就能看到这些新指标，指标端点里出现虚拟线程序列，就是条件分支命中的旁路证据。

单元测试对两档线程模型各有断言，虚拟档的一个用例长这样：

```java
@Test
@DisplayName("虚拟线程档：status 报 true，请求线程是 tomcat-handler- 虚拟线程")
void requestHandledByVirtualTomcatThread() {
    client.get().uri("/api/threads/status").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.virtualEnabled").isEqualTo("true")
            .jsonPath("$.requestThread").value(v ->
                    assertThat((String) v).contains("VirtualThread[").contains("tomcat-handler-"));
}
```

测试类用 `@SpringBootTest(properties = "spring.threads.virtual.enabled=true")` 拉起独立上下文，`@AutoConfigureRestTestClient` 注入 Boot 4 的 RestTestClient 发真实 HTTP 请求。`mvn test` 共 9 个用例全绿：平台档断言请求线程含 `exec-` 且不含 `VirtualThread`，虚拟档断言请求线程含 `VirtualThread[` 与 `tomcat-handler-`，连续 3 个请求拿到 3 个不同的虚拟线程名，并行接口的扇出线程不与请求线程重名。同一个工程、同一份主代码，两个测试类只差一个属性，线程名断言随开关整体切换，这是开关语义的直接回归证明。

## 压测对比

口径先交代清楚：我在同一台机器、同一轮里，两档先后各执行一次「冷启动 + 压测」。100 并发 × 400 请求，每请求串行 3 × 200ms 下游延迟，客户端是 venv Python 多线程 HTTP 客户端，预热 3 个请求后计时。机器负载有波动，两档数字必须取自同一轮才可比，跨轮对比会混入环境噪声；表里每个分位数都取自同一轮 400 个样本的中位口径（p50 即样本中位），吞吐用成功请求数除以墙钟时间。

| 指标 | 平台线程档 | 虚拟线程档 |
|---|---|---|
| 吞吐 | 32.9 req/s | 157.4 req/s |
| 客户端 p50 | 3017.4 ms | 610.8 ms |
| 客户端 p99 | 3088.2 ms | 652.9 ms |
| 客户端 max | 3089.7 ms | 656.8 ms |
| 应用侧耗时中位 | 601 ms | 603 ms |
| 请求线程去重数 | 20 | 400 |
| 成功 / 失败 | 400 / 0 | 400 / 0 |

两档的应用侧耗时中位几乎相同（601 与 603ms），说明下游模拟本身没有变化，差异全部来自线程模型。平台档吞吐我用手头的排队公式核算过：20 个工作线程 × (1 / 0.601s) ≈ 33.3 req/s，与实测 32.9 吻合；每个请求在队列里等前面 4 轮排空，p50 停在 3000ms 出头。虚拟线程档不再有 20 个的池上限，400 个请求各占一个 `tomcat-handler-N` 虚拟线程，吞吐 157.4 req/s 接近 100 并发 ÷ 0.6s 的理论上限，p99 从 3088ms 压到 652.9ms，只比单请求耗时高 50ms。吞吐倍数 4.78，正好约等于线程池上限 20 与并发 100 的比值关系。

线程名样本来自压测响应：平台档去重 20 个 `http-nio-18270-exec-N`，虚拟档去重 400 个 `VirtualThread[...tomcat-handler-N]`，两份样本我从响应里原样抄进正文，一个字符都没动。两档应用日志 ERROR 均为 0 行。

把池调大到 2000 再对比是另一条值得说明的对照路径。内存账面上 2000 个平台线程按默认 1MB 栈预留就是 2GB，还有调度器在 2000 个可运行实体间的切换成本；容量曲线没有变：线程数成了新的「池上限」，瓶颈从 20 挪到 2000 而已，排队结构原样保留。虚拟线程档不做这笔预留，400 个请求共享 8 个载体线程，栈按需增长，容量跟着并发走而不是跟着预留走。本篇没有实测 2000 线程档，这条对照写在这里作为决策参考，正文引用的实测数字全部来自上面这两档。

数字要按场景外推，这是我给你的判断。这个 4.78 倍来自「线程池上限是主要瓶颈、CPU 空闲」的设定；并发 100 与池上限 20 的比例决定倍数，把 `server.tomcat.threads.max` 调到 100 以上，两档差距会收窄到调度开销级别。虚拟线程消除的是「等」的成本，「算」的成本它一点都帮不上。

压测由一个验证脚本单次执行完成，两档各跑一轮「启动、等待就绪、压测、关停」，避免人肉操作混入变量：

```bash
# 每轮：起服务 -> 探测就绪 -> 压测 -> 强杀进程并确认端口释放
java -jar target/springboot4-virtualthreads-1.0.0.jar \
     --spring.threads.virtual.enabled=$virtual &
"$PY" stress_client.py 18270 100 400 200 result.json
```

客户端本身是 Python 多线程 HTTP 程序：100 个工作线程共享一个提交队列，先打 3 个预热请求再统一计时，逐请求记录客户端耗时、响应里的 `requestThread` 与 `costMs`。选自写客户端是因为本机没有 ab、wrk 这类压测工具，而 Python 线程池的 100 并发足以把 20 个 Tomcat 池线程打满。脚本输出就是上表的全部数字，我都留了底，正文与其逐字一致。

复现只需三步：`mvn package -DskipTests` 打出 jar，跑一遍仓库里附带的验证脚本完成两档取值，`mvn test` 回归线程名断言。验证脚本与两份应用日志（各含一遍启动记录与压测期间的完整日志）我都留了档，ERROR 行数在脚本末尾单独复核一遍，两档均为 0 才算本轮有效。

还有一个口径细节值得记下：p50 的 3017ms 与 610.8ms 之差，正是「排队时间」与「服务时间」的分野。排队论里并发系统有 M/M/c 近似公式，等待时间随并发与线程数之比急剧上升；实测 3017 ≈ (100/20 − 1) × 601 + 601，与 3 轮排队的估算吻合。看 p99 与 max 的接近程度还能确认没有长尾抖动，虚拟档 652.9 对 656.8，分布很紧。

![](https://static.xiongneng.me/virtualthreads-benchmark-20260920220845.png)

## pinned 与适用边界

虚拟线程的载体池默认并行度等于 CPU 核数（可用 `jdk.virtualThreadScheduler.parallelism` 调整），本机线程名里的 `ForkJoinPool-1-worker-1` 到 `worker-8` 就是这个默认值。载体这么少还能撑高吞吐，靠的是阻塞时卸载：虚拟线程在 socket 读、`Thread.sleep`、`Object.wait()` 等阻塞点会把载体让出来。

有一个 JDK 21 上的著名例外：`synchronized` 块内的阻塞。JEP 444 的实现里，JVM 按载体线程记录监视器归属，虚拟线程在 `synchronized` 内阻塞时无法卸载，只能连人带载体一起钉住（pin），钉住的载体不再参与调度。极端场景下所有载体都被钉住，应用停摆。JDK 21 时代的官方建议是把热点 `synchronized` 换成 `ReentrantLock`，后者不引发 pin。

JDK 24 交付了 JEP 491，JVM 改造后虚拟线程可以独立于载体持有监视器，`synchronized` 内阻塞时正常卸载，`Object.wait()` 同样处理，这一类 pin 基本清零；剩下的例外只有 native 方法与 FFM 回调中的阻塞、类初始化阻塞，以及 JDK 24 中已移除的 `jdk.tracePinnedThreads` 属性（它自身在关键路径打栈有缺陷，新诊断入口是 JFR 事件 `jdk.VirtualThreadPinned`）。这正是官方推荐 Java 24 的原因。改造的思路是把监视器的归属从「载体线程」挪给「虚拟线程自己」：阻塞时照常卸载，唤醒后重新挂载再重试获取，互斥语义不变。

热点代码从 `synchronized` 换成 `ReentrantLock` 的改法是机械的：

```java
private final ReentrantLock lock = new ReentrantLock();

public void syncCall() {
    lock.lock();
    try {
        // 原 synchronized 块内的阻塞调用
        downstream.call(200);
    } finally {
        lock.unlock();
    }
}
```

`lock()` 与 `unlock()` 之间的阻塞不会 pin 载体，等待的虚拟线程直接卸载；互斥语义与原来一致，多出来的是手动解锁的义务，所以 `unlock` 必须放在 finally 里。JDK 24 之后这段改造不再是必须项，新代码按团队习惯二选一即可。JDK 21 上我的工程实践分两步：先用 JFR 找 pin 点，把长阻塞的 `synchronized` 换成 `ReentrantLock`，能升级再升 JDK。

适用边界按负载类型划分。IO 等待型（RPC、HTTP、JDBC 查询、消息等待）收益最大，本篇 4.78 倍属于这类；CPU 密集型任务没有可让出的阻塞点，虚拟线程不会更快，还会增加调度开销。另一个边界是 `ThreadLocal`：百万级虚拟线程每个都持有自己的副本，缓存大对象时内存放大明显，JDK 的长期方向是 ScopedValue（JEP 446）。

还有一个结构性误区：开了开关，又把业务任务提交进 `Executors.newFixedThreadPool(20)` 这类固定池。池上限原样拦在中间，任务排队后仍由 20 个平台线程执行，虚拟线程的容量收益绕不过这道墙。程序式并发的正确姿势是 `newVirtualThreadPerTaskExecutor` 或 `Thread.ofVirtual()`，让「每任务一线程」的模型贯穿到业务层。

Boot 3.2 就引入了这个开关，升级到 4.1 的项目不用改属性名；变化的是内部实现与作用面归属，取证与排障时要多看几个 jar。

pin 与调度参数都有现成的运行时入口，排查时两条命令起步：

```bash
# 查看虚拟线程调度器的并行度与当前挂载关系
jcmd <pid> Thread.dump_to_file=/tmp/threads.json -json

# 记录一段负载，事后读 jdk.VirtualThreadPinned 事件
jcmd <pid> JFR.start duration=60s filename=/tmp/pinned.jfr
```

JSON 格式的线程转储里，每个虚拟线程带着载体信息与阻塞点栈；JFR 录完用 `jfr print --events jdk.VirtualThreadPinned` 过滤，事件里直接给出 pin 原因和耗时。JDK 21 上转储是排查虚拟线程的唯一全景工具（传统 `jstack` 不输出虚拟线程），JDK 24 起事件还增加了载体线程标识。

## 避坑指南

**坑一，配置了不存在的 name-prefix 属性。** 4.1.1 属性元数据里只有 `spring.threads.virtual.enabled`，没有 `spring.threads.virtual.name-prefix`；Tomcat 前缀硬编码 `tomcat-handler-`，`SimpleAsyncTaskExecutor` 的前缀沿用 `spring.task.execution.thread-name-prefix`（默认 `task-`），按组件分别查。我第一回就踩了这条。

**坑二，以为开关只影响 Tomcat。** 作用面覆盖 applicationTaskExecutor、任务调度器、RabbitMQ 监听器容器工厂、Redis 连接配置与 OTLP 指标导出，全量使用点见前表；评估开启影响时按这张清单过一遍，别只看 Web 层。

**坑三，JDK 21 上 synchronized 内长阻塞引发 pin。** 典型现场是单例服务的 `synchronized` 方法里做远程调用；用 JFR 的 `jdk.VirtualThreadPinned` 事件定位，热点代码换 `ReentrantLock`，或者升 JDK 24。

**坑四，线程池调优参数失效后还留着。** `server.tomcat.threads.max`、`spring.task.execution.pool.*` 开启后不生效也不报错；留下的配置会把排障的人引向错误方向，开开关的同时应删掉这些参数。

**坑五，纯定时任务应用开了开关后提前退出。** 调度器线程变成守护线程，JVM 里没有非守护线程时直接退出；加 `spring.main.keep-alive=true`，Boot 用非守护线程维持存活。

**坑六，把开关当加速器用在 CPU 密集接口上。** 载体并行度默认等于 CPU 核数，计算型负载没有阻塞点可让出，虚拟线程不提供额外算力；先判断接口的等待时间占比，再决定要不要开。

**坑七，ThreadLocal 缓存大对象叠加百万线程。** 每个虚拟线程一份副本，请求级缓存（用户上下文、大字典）会被放大到百万份；改成方法参数传递或等 ScopedValue 转正。

**坑八，请求线程里等自己的线程池。** 平台线程档下，池内任务再提交任务回同一个池并阻塞等待，池满时互相等待会饿死（本篇模拟下游刻意用进程内 sleep 规避了这一点）；虚拟线程档因每任务一线程天然规避，但代码设计上不应依赖档位。

**坑九，用线程数估算容量再套到虚拟线程档。** 平台线程档容量 = 池上限 ÷ 单请求耗时；虚拟线程档容量 ≈ 并发数 ÷ 单请求耗时，上限受下游与数据库连接池约束，压测口径必须包含真实下游延迟。拿平台档的容量公式去套虚拟线程档，九成会算错。

**坑十，诊断工具按 JDK 版本选。** `jdk.tracePinnedThreads` 在 JDK 21 可用、JDK 24 已移除；JFR 事件 `jdk.VirtualThreadPinned` 两个版本都有，JDK 24 起事件内容增加了 pin 原因与载体线程标识，跨版本脚本要分开写。

**坑十一，压测客户端并发不足会得出错误结论。** 客户端线程数低于服务端瓶颈时，两档都跑不满，差异被压平；客户端并发必须高于平台档池上限才能暴露排队，压测前先确认客户端自己不是瓶颈（CPU 与连接数都要看）。

**坑十二，虚拟线程异常处理沿用了线程池直觉。** 虚拟线程没有被池化复用，没有「线程泄漏后任务堆积在队列」的形态，出问题直接是线程数暴涨或下游被打挂；监控里要看的是虚拟线程创建速率与下游并发，而不是传统意义上的活跃线程数。

**坑十三，按平台线程的思路调 -Xss。** 虚拟线程的栈是托管在堆上的轻量结构，随调用深度增长收缩，`-Xss` 只影响平台线程；想把百万级虚拟线程塞进固定内存，关注的是堆上限与单个请求的栈深度，而不是线程栈参数。

## 小结

要不要开这个开关，我的标准只有一条：请求线程的时间花在哪。等待占比高的服务（RPC、HTTP、JDBC 查询）开它是稳稳的赚，CPU 密集的服务开了白开，还多付调度开销。开之前做两件事：按作用面清单把六个位点过一遍，清掉失效的线程池参数；JDK 21 上先用 JFR 查 pin 点，长阻塞的 `synchronized` 换 `ReentrantLock` 或者升 JDK 24。程序式并发用 `newVirtualThreadPerTaskExecutor` 和 `Thread.ofVirtual()`，别再往固定池里塞。

没解决的事我也直说：本篇的下游是进程内 sleep 模拟的，真实 RPC 的序列化开销、连接池竞争、下游限流带来的长尾，都得等接上真实下游再补一轮压测才有数；`ThreadLocal` 大缓存在百万线程下的内存放大，我目前只有机制分析，没有实测数字。这两块我后面找机会补上。

## 参考链接

- [Spring Boot 4.1 Reference - Virtual Threads](https://docs.spring.io/spring-boot/4.1/reference/features/spring-application.html)：`spring.threads.virtual.enabled` 开关、线程池属性失效、守护线程与 `spring.main.keep-alive` 的官方章节
- [Spring Boot 4.1 Reference - Servlet Web Applications](https://docs.spring.io/spring-boot/4.1/reference/web/servlet.html)：嵌入式 Tomcat 线程配置与 `server.tomcat.threads.*` 属性说明
- [OpenJDK JEP 444](https://openjdk.org/jeps/444)：Java 21 虚拟线程正式规范，载体调度、pinning 行为与 `jdk.tracePinnedThreads` 诊断
- [OpenJDK JEP 491](https://openjdk.org/jeps/491)：JDK 24 移除 `synchronized` 引发的 pinning，剩余 pin 场景清单与诊断变化
- [Apache Tomcat 11 - VirtualThreadExecutor](https://tomcat.apache.org/tomcat-11.0-doc/api/org/apache/tomcat/util/threads/VirtualThreadExecutor.html)：`tomcat-handler-` 前缀执行器的 API 文档
