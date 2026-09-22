---
title: SpringBoot4系列12 - 使用异步任务与线程池
slug: sb4-async
date: 2026-10-12 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Async, 线程池 ]
draft: false
---

前阵子我盯着一个下单接口，落库本身很快，麻烦都在落库之后：给客户发短信通知要 600 毫秒，积分入账要 700 毫秒，报表聚合要 800 毫秒。原来的写法是三件事按顺序排在一个方法里，我拿 curl 压了一把，接口响应 2155 毫秒，高峰期每个请求都把 HTTP 线程占住两秒多，吞吐量被这几件慢活按在地上。

![](https://static.xiongneng.me/async-sync-vs-async-20260922020330.png)

这三件慢事没有一件影响下单结果，它们只是要在下单之后发生。把「做」和「等结果」拆开，落库完成就把请求放行，三件慢事丢给线程池并行去跑，同一套流程的接口响应降到 23 毫秒。`@EnableAsync` 加 `@Async` 两个注解负责拆，线程池的形态负责兜住流量。

这篇全按 Spring Boot 4.1.1 加 Framework 7.0.9 的写法来。说实话，异步里的坑比注解本身多：自调用静默失效、void 和返回值的异常去处完全不同、ThreadLocal 一过线程就断、默认配置的线程池队列无界堆积。每个坑我都在工程里做成了可以当场复现的探针，插画、响应面板和旁边的文字取自同一轮运行，线程名与毫秒值每轮会有抖动，机制取值以所在章节的图为准。

## 编程模型与自动配置的事实

动笔写代码之前，我先把 import 和依赖摸了一遍。第一件事实：没有专属 starter。我在 Maven Central 上探了 4.1.1，`spring-boot-starter-task` 返回 404，独立的 spring-boot-task 模块也不存在。异步能力由两部分组成，常规 web 工程零额外依赖：

```text
编程模型   @EnableAsync / @Async / AsyncConfigurer
          org.springframework.scheduling.annotation     （spring-context 7.0.9）

自动配置   TaskExecutionAutoConfiguration / TaskExecutionProperties
          org.springframework.boot.autoconfigure.task   （spring-boot-autoconfigure 4.1.1）

定制点     ThreadPoolTaskExecutorBuilder / ThreadPoolTaskExecutorCustomizer
          org.springframework.boot.task                  （spring-boot 4.1.1）

异常处理   AsyncUncaughtExceptionHandler / SimpleAsyncUncaughtExceptionHandler
          org.springframework.aop.interceptor            （spring-aop 7.0.9）
```

四个包各有分工。`annotation` 包里是注解与代理装配逻辑；`autoconfigure.task` 包里是 Boot 的装配与属性类；`boot.task` 包里是构建器与定制器；`aop.interceptor` 包里是异常处理的两个接口与默认实现。我写 import 就按这张清单核对，对不上的一律以它为准。

第二件事实与默认执行器的归属有关。`TaskExecutionAutoConfiguration` 会创建一个名字叫 `applicationTaskExecutor` 的 `ThreadPoolTaskExecutor`，但它身上挂着 `@ConditionalOnMissingBean(Executor.class)`：容器里只要出现任何一个 `Executor` 类型的 bean，整套默认装配立刻退让。我自定义了两个池，然后拿一条测试把这个判断钉死：

```java
@Test
void applicationTaskExecutorBacksOffWhenExecutorBeansExist() {
    assertThat(context.containsBean("applicationTaskExecutor")).isFalse();
    assertThat(context.containsBean("applicationTaskExecutorAsyncConfigurer")).isFalse();
}
```

两个断言都是 false。挺有意思，自己建池的工程里 Boot 的默认执行器压根不存在，与「感觉上还有个默认池兜底」的直觉相反，排错时省得在两个池之间来回猜。

第三件事实是 Boot 替 @Async 补的接线。上下文里没有用户自己的 `AsyncConfigurer` 时，Boot 注册一个 `applicationTaskExecutorAsyncConfigurer`（实现 AsyncConfigurer，条件同样是 `@ConditionalOnMissingBean`），把 `@Async` 不写限定符的方法接到 `applicationTaskExecutor`。我声明了自己的 AsyncConfigurer 之后它就退位，默认执行器与异常处理器都由这份声明决定。

装配出来的执行器按平台线程与虚拟线程分两条分支：

```text
PLATFORM（默认）  ThreadPoolTaskExecutor，属性取 spring.task.execution.*
VIRTUAL          SimpleAsyncTaskExecutor，spring.threads.virtual.enabled=true 时启用，
                 bean 名字 applicationTaskExecutorVirtualThreads
```

代理机制也在这里说清。`@EnableAsync` 的 `proxyTargetClass` 属性默认值是 false，但 Boot 的 `spring.aop.proxy-target-class` 默认是 true，Boot 应用里的 @Async 代理实际是 CGLIB 子类代理。`@Async` 的 `value` 属性是执行器 bean 的限定符，填了就走指定名字的池，不填走 AsyncConfigurer 给的默认池。

属性前缀没有改名，`spring.task.execution` 的注解定义在字节码里原样可查。属性类构造器里的默认值，我逐个翻出来验过：

```text
spring.task.execution.thread-name-prefix              默认 task-
spring.task.execution.pool.core-size                  默认 8
spring.task.execution.pool.max-size                   默认 Integer.MAX_VALUE
spring.task.execution.pool.queue-capacity             默认 Integer.MAX_VALUE（无界）
spring.task.execution.pool.allow-core-thread-timeout  默认 true
spring.task.execution.pool.keep-alive                 默认 60s
spring.task.execution.pool.shutdown.await-termination / await-termination-period
spring.task.execution.mode                            AUTO / FORCE
spring.task.execution.propagate-context               装配 ContextPropagatingTaskDecorator
```

默认组合里最要命的是队列：core 8、queue 无界。无界队列永远填不满，线程数永远停在 8，`max-size` 配了也到不了。高峰期的任务全部堆在队列里不报错，内存涨上去你才发现。我在工程里做的关键演示之一，就是把队列改小，让拒绝路径现出原形。

## 依赖和配置

工程依赖清单里没有任何异步坐标，web 与 JDBC 就够了：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

订单落 MySQL，表结构与前几篇一致。配置文件里与异步相关的只有一处 `spring.task.execution`，再加一组演示耗时：

```yaml
spring:
  task:
    execution:
      thread-name-prefix: biz-
      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 100
      shutdown:
        await-termination: true
        await-termination-period: 5s

app:
  chaos:
    sms-millis: 600
    points-millis: 700
    report-millis: 800
```

线程名前缀 `biz-` 是我给排查留的记号，后面所有验证输出里的线程名都以它开头，日志里一眼能认出哪些活是异步线程干的。三件慢事的耗时收进配置，接口响应时间的对比来自这组值：600 加 700 加 800，同步串行 2100 毫秒起步。

## 两条路配线程池

异步的执行质量由线程池决定。属性已经配好，怎么让它流进自己声明的池里，看装配代码：

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 属性路：builder 的默认值来自 spring.task.execution.*，这里只 build()。 */
    @Bean
    public ThreadPoolTaskExecutor bizExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
```

`bizExecutor` 没有一行 setter，core 4、max 8、队列 100、前缀 `biz-` 全部来自 yml。构建器把这些属性吃进默认值，属性文件与代码各管各的，改池参数不用动 Java。我在测试里对池做了逐项断言，四个属性值与 yml 完全一致，属性到池的通路被钉死。

第二条路是完全接管，这是我给演示拒绝行为准备的有界池：

```java
@Bean
public ThreadPoolTaskExecutor boundedExecutor(RejectionCounter counter) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(2);
    executor.setThreadNamePrefix("bounded-");
    executor.setRejectedExecutionHandler((r, pool) -> {
        counter.increase();
        throw new RejectedExecutionException("boundedExecutor 已满（core=2, max=4, queue=2）");
    });
    executor.initialize();
    return executor;
}
```

core 2、queue 2、max 4，一组刻意收窄的参数。拒绝策略换成了自定义实现：拒绝前先计数再抛出，这个计数器就是后面实测里「拒绝 4 次」的来源。这条链路的分配规则要先记一遍：任务来了先占 core，core 满了进队列，队列满了扩线程到 max，max 也满了才触发拒绝。JDK 默认的 `AbortPolicy` 什么也不做直接抛，`ThreadPoolTaskExecutor` 把它包成 `TaskRejectedException` 丢回提交方。

两个池就位之后，@Async 的默认走向需要一份声明：

```java
@Bean
public AsyncConfigurer asyncConfigurer(@Qualifier("bizExecutor") Executor bizExecutor,
                                       UncaughtCounter uncaughtCounter) {
    return new AsyncConfigurer() {

        @Override
        public Executor getAsyncExecutor() {
            return bizExecutor;
        }

        @Override
        public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return uncaughtCounter;
        }
    };
}
```

`AsyncConfigurer` 只有两个方法，且都是 default：一个给默认执行器，一个给未捕获异常处理器，按需覆盖。这份声明同时解决两件事：不写限定符的 @Async 走 `bizExecutor`，void 方法的异常流进自己的处理器。我声明它的那一刻，Boot 自动装配的那份接线整体退位。

![](https://static.xiongneng.me/async-pool-two-paths-20260922020330.png)

顺带记一个定制器的事实：`ThreadPoolTaskExecutorCustomizer` 存在于 `org.springframework.boot.task` 包，注册成 bean 后被构建器自动收集，改线程名、换装饰器这类浅定制够用。与它并排的还有 `SimpleAsyncTaskExecutorCustomizer`、`ThreadPoolTaskSchedulerCustomizer`、`SimpleAsyncTaskSchedulerCustomizer` 三个，全是具体实现类级别的定制器，没有泛型的 `TaskExecutorCustomizer`。

## @Async 的两种返回与异常的去处

三件慢事的代码长这样，一个 void、一个带返回值：

```java
@Async
public void sendOrderSms(String taskId, String orderNo) {
    smsOnce(taskId, orderNo);
}

@Async
public CompletableFuture<Integer> creditPoints(String taskId, String orderNo) {
    return CompletableFuture.completedFuture(pointsOnce(taskId, orderNo));
}
```

方法体里没有一行线程管理的代码，注解接管全部调度。入参里带着 `taskId`，任务一开始就在台账上登记自己的线程名与开始时间，这就是后面所有线程证据的来源。返回值分两种：void 的短信任务发完就结束；积分任务返回 `CompletableFuture`，调用方拿着它等结果。

两种返回对应两条完全不同的异常去处。void 方法的异常没有地方搁，框架把它交给 `AsyncUncaughtExceptionHandler`，默认实现 `SimpleAsyncUncaughtExceptionHandler` 记一条 ERROR 日志了事。我换成自己的处理器：

```java
@Component
public class UncaughtCounter implements AsyncUncaughtExceptionHandler {

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        count.incrementAndGet();
        String taskId = params.length > 0 && params[0] instanceof String s ? s : null;
        boolean tracked = taskId != null && ledger.fail(taskId, "void-exception", ex);
        log.warn("ASYNC_UNCAUGHT method={} taskId={} tracked={} error={}",
                method.getName(), taskId, tracked, ex.toString());
    }
}
```

处理器做三件事：计数加一、把台账里的对应记录标成 FAILED、按 WARN 记日志。日志级别打 WARN 是我的选择，异常已经有了计数与台账两个去处，应用的 ERROR 指标保持干净。验证里这个计数与日志 WARN 行数都是 1，两个口径对得上。

CompletableFuture 方法的异常走另一条路，处理器的签名里没有它的位置。异常被装进 future，调用方执行 `get()` 的那一刻才以 `ExecutionException` 的形式浮出来：

```java
CompletableFuture<Integer> future = futureRegistry.get(taskId);
if (future != null && future.isDone()) {
    try {
        body.put("points", future.get());
    } catch (ExecutionException e) {
        body.put("futureException", e.toString());
    }
}
```

实测输出里，future 异常任务的台账状态是 FAILED，查询接口的 `futureException` 字段是 `java.util.concurrent.ExecutionException: java.lang.IllegalStateException: 演示异常：积分服务超时`，同一轮里 `uncaughtHandled` 保持不变。异常去处的分流规则由此钉死：void 进处理器，future 等调用方来取，写代码时按返回类型决定防线设在哪一侧。

![](https://static.xiongneng.me/async-exception-routing-20260922020330.png)

## 自调用失效与 ThreadLocal 断层

自调用是 @Async 最常见的静默失效，我测试就栽在这里。同一个 bean 里方法 A 调方法 B，B 上标了 @Async，调用走的却是 `this` 引用，代理完全没有机会切进去，B 就在 A 的线程里同步跑完。工程里把两条调用路径并排放进一个探针：

```java
// 路径一：this 调用，走不到代理
this.asyncInner(taskId, "self-call");

// 路径二：从容器注入的代理调用，真异步
self.asyncInner(taskId, "proxied-call");
```

`self` 是注入到自身类型的字段，拿到的是容器里被 CGLIB 包装过的代理。这里有一个 Boot 侧的细节：Bean 默认不允许循环引用，直接 `@Autowired` 自己的类型会启动失败，报 Requested bean is currently in creation，字段上要加 `@Lazy` 才能拿到延迟解析的占位。

探针的输出一次说清两条路径：

```text
httpThread        = http-nio-18120-exec-7
selfCallThread    = http-nio-18120-exec-7   （与 HTTP 线程相同，同步执行）
proxiedCallThread = biz-2                   （异步线程）
selfCallAsync     = false
```

this 调用的执行线程与 HTTP 线程逐字相同，注解没有生效；代理调用落在 `biz-2`。判断项目里有没有踩这个坑，最快的办法就是把两类线程名都打出来比对，排除下来九成是这个原因。

ThreadLocal 是另一条断层。HTTP 线程写入的值，异步线程读出来是 null：

```java
@Async
public CompletableFuture<String> readTraceId(String taskId) {
    TaskLedger.Entry entry = ledger.begin(taskId, "thread-local");
    sleep(100);
    String value = TraceIdHolder.get();
    ledger.done(taskId, entry);
    return CompletableFuture.completedFuture(value);
}
```

实测输出：HTTP 线程里是 `trace-0b79adcd`，异步线程 `biz-3` 里是 null。链路追踪、安全上下文（`SecurityContext`）、事务同步器都挂在这类 ThreadLocal 上，跨线程之后全部丢失。要传递就显式传参，或者给池挂 `TaskDecorator` 把上下文复制过去，Boot 还提供了开关：`spring.task.execution.propagate-context=true` 时自动装配 `ContextPropagatingTaskDecorator`，处理 `ThreadContext` 这类标准上下文。

![](https://static.xiongneng.me/async-self-invocation-20260922020330.png)

## 拒绝策略的现场

拒绝行为要有一组够小的参数才能看到。我向 `boundedExecutor` 灌 10 个各睡 1 秒的任务：

```java
for (int i = 0; i < 10; i++) {
    try {
        boundedExecutor.execute(() -> sleepQuietly(1000));
        accepted++;
    } catch (TaskRejectedException e) {
        // 拒绝计数在自定义 RejectedExecutionHandler 里累加
    }
}
```

提交动作在几毫秒内完成，池的分配是确定性的：2 个占 core，2 个进队列，2 个触发扩容到 max，剩下 4 个被拒绝。实测输出：

```text
tasks = 10   accepted = 6   rejected = 4   rejectionCounter = 4
```

提交侧的 `TaskRejectedException` 与计数器的 4 一致。把这条链路换成默认配置再看一遍就明白风险在哪：core 8 加无界队列，任务只进队列不扩容也不拒绝，堆积的唯一表现是内存与延迟。有界队列加明确的拒绝策略，溢出从「慢性病」变成「当场报警」。

![](https://static.xiongneng.me/async-rejection-timeline-20260922020330.png)

## 完整案例，跑起来看

同一个下单动作，工程里给出异步与同步两个入口。同步入口先把三件慢事串行做完：

```bash
curl -s -X POST "http://localhost:18120/api/orders/sync" \
     -H "Content-Type: application/json" \
     -d '{"orderNo":"SO80001","amount":"199.50"}'
```

响应 201，`elapsedMillis` 是 2165。这就是被拖死的版本，数字与配置里三个耗时的和严丝合缝。

异步入口只提交不等待：

```bash
curl -s -X POST "http://localhost:18120/api/orders" \
     -H "Content-Type: application/json" \
     -d '{"orderNo":"SO80002","amount":"299.00"}'
```

响应 201，返回 `taskId`，`elapsedMillis` 是 18。三件慢事在后台并行跑，POST 的响应体里看不出它们的下落，去向在任务台账接口里：

```bash
curl -s "http://localhost:18120/api/tasks/{taskId}"
```

![](https://static.xiongneng.me/async-panels-20260920005801.png)

台账面板里一次看清三类证据。聚合状态是 DONE；三条执行记录分别是 sms、points、report，耗时 609、782、807 毫秒，线程名是 `biz-1`、`biz-2`、`biz-3`，三条不同线程并行取活；积分任务的返回值 points 等于 10，future 正常完成时查询接口直接给出结果。三个任务同时投递、核心池 4 根线程充足，互不排队，毫秒级响应就是这么来的。

![](https://static.xiongneng.me/async-order-task-panels-20260920005753.png)

探针拼板是另一组运行期证据：自调用探针里 `selfCallThread` 与 `httpThread` 同为 `http-nio-18120-exec-3`，代理调用落在 `biz-4`；ThreadLocal 探针里 HTTP 线程的 `trace-626533f9` 在异步线程 `biz-1` 上读出 null；void 异常之后计数器 `uncaughtHandled` 等于 1；future 异常任务的台账状态 FAILED，`futureException` 字段是完整的 `ExecutionException` 链。截图轮与开篇引子的 2155 对 23 来自不同的运行轮次，毫秒级抖动不改变量级结论。拒绝策略在验证脚本一轮里接受 6 拒绝 4，两轮的应用日志都是 ERROR 0 行，`ASYNC_UNCAUGHT` 的 WARN 与 `uncaughtHandled` 计数逐轮对得上。执行记录是这套演示的中枢，每条记录带着线程名、耗时、异常类型，行为变化当场能对出来。

## 这一篇的测试怎么写

17 个用例全连真实 MySQL，异步相关的断言集中在三类：配置事实、异常分流、并行行为。配置事实的用例在前面已经见过，并行行为的断言我用 Awaitility 轮询台账，不打时间的主意：

```java
Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
    Map<String, Object> body = taskBody(taskId);
    assertThat(body.get("status")).isEqualTo("DONE");
    List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
    assertThat(entries).hasSize(3);
});
```

轮询直到三个任务全部 DONE，再对台账里的线程名做逐条断言：每条都以 `biz-` 开头，不同线程的数量大于等于 2。异步测试最容易写成 `Thread.sleep(3000)` 然后祈祷，我早年就这么干过，闹过测试时好时坏的笑话；Awaitility 把「等到位」变成可重试的条件断言，快机器上几百毫秒就过，慢机器上也不脆。

异常分流的两个用例分别钉住两条去处：

```java
// void 异常：处理器计数恰好 +1，台账 FAILED，异常类型在 error 字段
assertThat(uncaughtCounter.count()).isEqualTo(before + 1);
assertThat(response).contains("java.lang.IllegalStateException");
assertThat(response).contains("短信通道 503");

// future 异常：get() 处捞到 ExecutionException
assertThat(response).contains("java.util.concurrent.ExecutionException");
```

其余用例的分布：配置事实 5 个（属性进池、有界池参数、默认执行器退让、AsyncConfigurer 接线、`@EnableAsync` 在位），接口 4 个（异步毫秒级、同步不低于 2 秒、校验 400、查询 404），主流程 4 个（三任务并行、台账聚合、自调用失效、ThreadLocal 断层），异常分流 3 个，拒绝策略 1 个。拒绝策略的断言是确定性的：灌 10 个任务，接受 6、拒绝 4，两个数都与池参数的推导一致。

## 避坑指南

**坑一，默认队列无界，堆积不报警。** `queue-capacity` 默认 `Integer.MAX_VALUE`，core 8 之外永远不扩容，max-size 形同虚设。高峰期任务全部压在队列里，内存涨、延迟升，日志里一个报错都没有。我建议上生产前把队列改小、把拒绝策略显式定下来，让溢出当场暴露。

**坑二，this 自调用让 @Async 静默失效。** 同类内部调用走不到代理，方法退化为同步执行，没有任何报错。判断方法很简单：把执行线程名打出来，与调用方线程一致就是实锤。解法是拆到另一个 bean，或者注入自己的代理（字段加 `@Lazy`，否则循环引用直接启动失败）。

**坑三，void 与返回值的异常去处不同。** void 方法的异常进 `AsyncUncaughtExceptionHandler`，默认实现只记一条 ERROR 日志；CompletableFuture 的异常待在 future 里，调用方不调 `get()` 就永远没人知道。防线要按返回类型设：void 靠处理器计数与告警，返回值靠调用方 try 住 `ExecutionException`。

**坑四，ThreadLocal 与安全上下文不跨线程。** 链路追踪 ID、`SecurityContext`、事务同步器挂在 ThreadLocal 上，线程一换全部为 null。显式传参最稳；要自动传递就挂 `TaskDecorator`，或开 `spring.task.execution.propagate-context=true`。事务边界的坑在 06 篇写过一遍，跨线程同样生效。

**坑五，自己声明 Executor bean 之后，默认执行器整套退让。** `@ConditionalOnMissingBean(Executor.class)` 的判断范围是所有 Executor 类型，自定义池一出现，`applicationTaskExecutor` 与 Boot 的 AsyncConfigurer 接线一起消失。此时 @Async 的默认执行器由自己的 AsyncConfigurer 决定，不声明它，默认池的解析就会落到不确定的分支上。

**坑六，@Async 方法要 public、要从外部调。** 代理拦截的是外部进入的调用，private 方法标了注解也不生效，同类内部调用同样不生效。方法返回 `Future` 之外的自定义类型时，框架按同步方法处理，返回值原样带回。

**坑七，虚拟线程开关会换掉执行器实现。** `spring.threads.virtual.enabled=true` 时默认执行器从 `ThreadPoolTaskExecutor` 换成 `SimpleAsyncTaskExecutor`，bean 名字变为 `applicationTaskExecutorVirtualThreads`，pool 参数的语义随实现变化。依赖具体实现类的代码先 instanceof 确认，测试里钉一遍最稳。

**坑八，优雅停机要在属性里声明。** `shutdown.await-termination` 加 `await-termination-period` 让在跑的任务执行完再退。异步任务写到一半被 kill，积分入账落在半截，重试逻辑要在业务侧自己兜住。积分这类天然可重入的任务靠幂等键防重复，通知类任务重复发一次的代价要事先想清楚。

## 小结

选型建议就三句。常规 web 工程把池参数收进 `spring.task.execution`，经 `ThreadPoolTaskExecutorBuilder` 建池，别拿默认的无界队列裸奔；要自己掌控执行策略，就手工建池配好拒绝策略，再把 `AsyncConfigurer` 接过来，让默认执行器与异常处理器都听你的；异步测试用 Awaitility 轮询任务台账，不赌固定时延。异常防线按返回类型设，上下文要跨线程就显式传参或挂 `TaskDecorator`。

没解决的事也直说：本篇只给了单接口串行与异步两组响应数，没有做并发压测基准；拒绝策略是确定性推导加一轮实测，持续高压下队列深度与扩容的动态行为我没观察。这两块等我补上压测场景再谈，本文不给没测过的结论。

## 参考链接

- [Spring Framework Task Execution and Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)：`@Async` 注解、`AsyncConfigurer` 与异常处理链的官方说明
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)：`spring.task.execution.*` 属性清单与 task 自动配置的行为
- [ThreadPoolTaskExecutorBuilder](https://docs.spring.io/spring-boot/4.1/api/java/org/springframework/boot/task/ThreadPoolTaskExecutorBuilder.html)：构建器链式方法的 API 文档
- [spring-boot-autoconfigure 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-autoconfigure/4.1.1/)：本文包位置与属性前缀的制品实证来源
