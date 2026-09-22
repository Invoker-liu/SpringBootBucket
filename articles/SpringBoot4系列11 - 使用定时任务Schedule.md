---
title: SpringBoot4系列11 - 使用定时任务Schedule
slug: sb4-schedule
date: 2026-10-11 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Schedule, 定时任务 ]
draft: false
---

上一章的 Spring Batch 解决的是「算得完」，这一篇解决「准时跑」。场景还是订单业务：每天凌晨扫对账文件，每隔几秒把超时未支付的订单取消掉，固定节拍刷新一次报表快照。这类活的共同点是间隔固定、单次很轻、一个都不能长期缺席。

定时任务最伤人的两种故障都是静默的。一种是慢任务拖垮全局：黑名单同步调了一次超时的远端接口，执行线程被占住 8 秒，这 8 秒里取消订单、扫对账文件、刷新报表全部排队。业务侧看到的现象是取消动作集体迟到十几秒，对账文件晚了一个刻度才处理，日志里没有任何报错，线程名看起来一切正常。另一种是 cron 写错：表达式合法、应用正常启动、任务一声不吭地按错误的节奏跑，或者要等到凌晨三点才轮到它。说实话，我第一次帮人排查这类问题时盯着启动日志翻了半个钟头，异常一条都没有，只有节奏错了。看不见的故障，比当场炸掉的难缠得多。

![](https://static.xiongneng.me/schedule-single-thread-stall-20260922014350.png)

Spring 的调度编程模型用 `@EnableScheduling` 加 `@Scheduled` 两个注解解决「怎么定义任务」，用可替换的 `TaskScheduler` 解决「在哪儿跑」。这篇全按 Spring Boot 4.1.1 加 Framework 7.0.9 的写法来，所有行为、包名、属性前缀我都在这个组合上实测过，每段代码配的数字都来自同一份验证脚本一口气打出来的原始记录。

工程在 `springboot4-schedule` 目录，18 个测试全连真实 MySQL。三个业务任务加四类 HTTP 探针，把停摆、异常、动态改节奏这些运行期行为全部做成可以当场复现的演示。我边跑边看，比干讲机制踏实。

## 编程模型与自动配置的事实

写代码前先确认 import 和依赖。定时任务在 Boot 4 里的第一件事实：**没有专属 starter**。我去 Maven Central 上探测 4.1.1 版本，`spring-boot-starter-task` 返回 404，独立的 spring-boot-task 模块也不存在。调度能力由两部分组成，常规 web 工程零额外依赖：

```text
编程模型   @EnableScheduling / @Scheduled / @Async
          org.springframework.scheduling.annotation     （spring-context 7.0.9）

自动配置   TaskSchedulingAutoConfiguration / TaskExecutionAutoConfiguration
          org.springframework.boot.autoconfigure.task   （spring-boot-autoconfigure 4.1.1）

定制点     ThreadPoolTaskSchedulerBuilder / ThreadPoolTaskSchedulerCustomizer
          org.springframework.boot.task                  （spring-boot 4.1.1）
```

三个包各有分工。`annotation` 包里是应用天天写的注解与扫描它们的 `ScheduledAnnotationBeanPostProcessor`；`autoconfigure.task` 包里是 Boot 的装配逻辑与两个属性类；`boot.task` 包里是构建器与定制器，线程池的定制动作全部发生在这个包。写 import 时按这张清单核对，资料里的包名与它对不上就以它为准。

第二件事实是自动配置的装配条件。`TaskSchedulingAutoConfiguration` 本体只注册一个 `LazyInitializationExcludeFilter`，作用是让标注了 `@Scheduled` 的 bean 在 lazy 应用里不被跳过初始化，它生效的条件是容器里已经出现调度注解处理器，也就是说应用加了 `@EnableScheduling` 它才装配。创建调度线程池的是内部的 `DefaultTaskSchedulerConfiguration`，按平台线程与虚拟线程两条分支出 `taskScheduler` bean：

```text
PLATFORM（默认）  ThreadPoolTaskScheduler，池大小取 spring.task.scheduling.pool.size
VIRTUAL          SimpleAsyncTaskScheduler，spring.threads.virtual.enabled=true 时启用
```

属性前缀没有改名，`spring.task.scheduling` 与 `spring.task.execution` 的注解定义在字节码里原样可查。调度侧的全部属性一览：

```text
spring.task.scheduling.pool.size                 默认 1
spring.task.scheduling.thread-name-prefix        默认 scheduling-
spring.task.scheduling.simple.concurrency-limit  虚拟线程实现的并发上限
spring.task.scheduling.shutdown.await-termination
spring.task.scheduling.shutdown.await-termination-period
```

`pool.size` 的默认值我要单独点一次：属性类的构造器里写的是常数 1，所有任务串行共用一根线程，这就是停摆问题的根源。`thread-name-prefix` 默认是 `scheduling-`，挺有意思的一个细节：多线程接管之后把它换成带业务含义的前缀，日志里一眼就能认出哪根线程在跑哪个任务，排查省力不少。

任务的路由由 Framework 7 的 `TaskSchedulerRouter` 承担。它实现 `TaskScheduler` 接口，按 `@Scheduled(scheduler = "beanName")` 的限定符把不同任务派给不同的线程池，不写限定符就走名字叫 `taskScheduler` 的默认池。`@Scheduled` 注解上还有一个少有人用的常量：`cron` 属性填短横线字符即整体禁用该任务，配置中心下发开关时可以借用这个约定。

![](https://static.xiongneng.me/schedule-model-map-20260922014350.png)

## 依赖和配置

工程的依赖清单里没有任何调度坐标，web 与 JDBC 就够了：

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

三个业务任务的数据都落 MySQL：订单表给超时取消任务，对账文件登记表给扫描任务，日报表给统计任务。表结构由 `schema.sql` 幂等创建，`IF NOT EXISTS` 保证重复启动不出事。配置文件里与调度相关的只有三处，全部是自定义属性，调度本身的默认值一个都不覆盖：

```yaml
app:
  scheduler:
    pool-size: ${SCHEDULER_POOL:1}
    thread-name-prefix: order-sched-
  order:
    timeout-seconds: 15
  chaos:
    slow-millis: 8000
```

`pool-size` 留了环境变量入口，我的停摆实验就是靠 `SCHEDULER_POOL=1` 与 `SCHEDULER_POOL=4` 两次启动对比做出来的。超时阈值演示用 15 秒，生产里的同类业务一般是 15 分钟，改一个属性值就行。慢调用耗时收进配置，停摆实验的时长可控。演示任务的间隔全部用秒级，生产的定时任务一般是分钟级或小时级，节奏值换成 cron 表达式即可，机制不变。

## 三种触发方式怎么选

`@Scheduled` 的计时属性有三个，语义差别在「上一次执行的哪一刻」算起：

```java
@Scheduled(cron = "0/5 * * * * *")
public void scanReconFiles() { ... }

@Scheduled(fixedDelay = 3000, initialDelay = 4000)
public void cancelTimeoutOrders() { ... }

@Scheduled(fixedRate = 4000, initialDelay = 6000)
public void refreshDailyReport() { ... }
```

cron 按墙钟对齐，Spring 支持 6 域表达式（秒 分 时 日 月 周），秒位是 0 和 5 的倍数才触发，错过的刻度不补。fixedDelay 从上一次执行「结束」时刻起算，执行再久也不会与自己重入，适合清理型任务。fixedRate 按「开始」时刻对齐节拍，被别的任务拖住时顺延但不并发重入，适合有节拍语义的统计型任务。三个属性写在一个注解里互斥，一个方法只能选一种。

![](https://static.xiongneng.me/schedule-trigger-timeline-20260922014350.png)

几个辅助属性按需叠加。`initialDelay` 让任务避开应用启动高峰，报表任务延迟 6 秒是给数据源连接留时间；这两个属性都有 String 变体 `initialDelayString` / `fixedDelayString`，值可以从配置文件注入；`timeUnit` 改变数值单位；`zone` 只对 cron 生效，跨时区部署的对账任务要显式指定。

选型的判断依据是任务的性质。对账扫描要「每 5 秒看一眼有没有新文件」，cron 最直观；超时取消要「清完一轮歇 3 秒再来」，fixedDelay 不会在慢查询时堆积；报表要「固定节拍出数」，fixedRate 对齐感最强。三种写法在本工程里各占一个任务，每一轮的运行期行为我都留了原始记录，逐项可查。

## 线程池停摆与接管

默认配置下三个任务挤在一根线程上，这个现象可以精确复现。我先用默认配置启动，让黑名单同步任务睡 8 秒，然后量对账扫描任务两次执行的间隔：正常节奏是 5 秒，实测最大间隔 11.5 秒，18 秒的窗口里出现 2 次 8 秒级的断档。同一轮里全部任务的执行线程集合只有一个元素：`order-sched-1`。三个任务在轮流用同一根轨道，这就是铁证。

解法是手工声明一个同名的 `taskScheduler` bean，把池大小、线程名前缀、异常处理器三件事一次配齐：

```java
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${app.scheduler.pool-size:1}") int poolSize,
            @Value("${app.scheduler.thread-name-prefix:order-sched-}") String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(new SchedulerErrorHandler());
        return scheduler;
    }
}
```

bean 名字取 `taskScheduler` 有两层作用。一是自动配置声明了 `@ConditionalOnMissingBean`，手工 bean 一出现整套默认装配立刻退让；二是 Framework 7 的 `TaskSchedulerRouter` 默认就把任务派给这个名字的池，不用再做任何绑定。`@EnableScheduling` 也放在这个类上，开关与池在一个地方，看配置时不用翻第二个文件。

接管之后用同样的实验重跑：黑名单任务照旧睡 8 秒，对账扫描的两次执行最大间隔回到 5.0 秒，一次断档都没有；执行记录里的线程集合变成 `order-sched-1` 到 `order-sched-4` 四个。慢任务还在慢，但它只堵住自己的那条轨道。这个结果比我预期的还干净，四根线程一上，停摆直接消失。

![](https://static.xiongneng.me/schedule-pool-customize-20260922014350.png)

不想整池接管还有一条轻量的路：`ThreadPoolTaskSchedulerCustomizer`。注册一个实现 `customize(ThreadPoolTaskScheduler)` 的 bean，自动配置出的池会把它收集进去，改池大小、换线程名都够用。构建器 `ThreadPoolTaskSchedulerBuilder` 上的能力更全，`awaitTermination`、`taskDecorator`、`customizers` 一串链式方法，适合在别的配置类里组装自己的池。两条路的边界在 `ErrorHandler`：customizer 路径拿不到 `setErrorHandler` 的时机，要挂自定义异常处理器就得像上面那样整池接管。我一开始想走 customizer 偷懒，翻到这条边界才回头整池重写，闹了个小笑话。

## 异常处理：不重试，也不停摆

任务方法抛出异常后框架做什么，是定时任务上线前必须想清楚的问题。答案在调度器的装饰链里：每次触发被包成 `DelegatingErrorHandlingRunnable`，异常交给 `ErrorHandler` 处理完就结束，没有重试，也没有任何取消动作，下一次调度按原计划触发。默认的处理器行为是记一条日志然后放过，任务在框架眼里不会「失败到停机」。

默认行为能兜住存在性，兜不住可见性：异常只有一条框架日志，应用侧拿不到钩子，失败的次数、类型、分布全都要靠人翻日志。自己声明 `taskScheduler` 之后可以换成自己的实现：

```java
public class SchedulerErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(SchedulerErrorHandler.class);

    @Override
    public void handleError(Throwable t) {
        log.warn("SCHED_TASK_ERROR 定时任务执行失败，等待下一次调度: {}", t.toString());
    }
}
```

处理器的契约决定了语义：方法返回即视为处理完毕，调度器继续排下一次。日志级别打 WARN 是本工程的选择，失败次数与类型已经落在执行记录里，由监控体系去消费，异常不污染应用的 ERROR 指标。

工程里用一个故障注入任务实证这套语义：

```java
@Scheduled(cron = "0/6 * * * * *")
public void poisonProbe() {
    runPoisonOnce("cron");
}

public void runPoisonOnce(String trigger) {
    if (!poison) {
        return;
    }
    RuntimeException failure = new IllegalStateException("演示异常：远端对账接口 503");
    journal.record("poison", trigger, thread, "failed", failure.toString(), cost);
    throw failure;
}
```

失败先落执行记录再抛出，顺序有讲究：抛出之后代码不会回来，记录动作必须放在前面。开关打开 16 秒，cron 每 6 秒一次，执行记录里落了 3 条 failed，两条相邻失败的时间间隔实测 6.0 秒和 6.0 秒，与 cron 节奏严丝合缝。同一轮的应用日志 ERROR 0 行，`SCHED_TASK_ERROR` 的 WARN 恰好 3 行，两个口径互相印证。16 秒里没有任何一次多余的执行，框架没有重试，也没有漏掉一次触发。这个结果挺有意思：异常发生了三次，调度节奏一丝没乱，「不重试也不停摆」几个字全在数据里。

![](https://static.xiongneng.me/schedule-error-flow-20260922014350.png)

顺带把 `@Async` 的位置说清：它与 `@Scheduled` 同在 `org.springframework.scheduling.annotation` 包，共用 `spring.task.execution` 那一套执行器属性，但触发机制截然不同，一个是事件驱动一次一跑，一个是时间驱动按表触发。两套线程池在 Boot 里是两个 bean，池大小分别用 `spring.task.execution.pool.*` 与 `spring.task.scheduling.pool.*` 控制，不要混着调。

## 动态 cron 与手动触发

注解上的 cron 在启动期定死，要运行期改节奏就不能把表达式写死在注解里。做法是 `SchedulingConfigurer` 注册 `TriggerTask`，cron 值放进一个可替换的持有者：

```java
public class DynamicCronTrigger implements Trigger {

    private final AtomicReference<String> cron;

    public DynamicCronTrigger(String initialCron) {
        this.cron = new AtomicReference<>(validate(initialCron));
    }

    public String update(String newCron) {
        String validated = validate(newCron);
        cron.set(validated);
        return validated;
    }

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        return new CronTrigger(cron.get()).nextExecution(triggerContext);
    }
}
```

`Trigger` 接口只在调度器需要排下一次时被调用，每次都按「当前值」解析，HTTP 接口改完值，下一次调度立即按新节奏算。`update` 里先过一遍 `CronExpression.parse`，非法表达式当场抛 `IllegalArgumentException`，Controller 层把它翻成 400。`AtomicReference` 保证读写两侧不撞车，改值的请求与调度线程的读取并发进行也没有中间态。

任务的注册与注解方式并存：

```java
@Configuration
public class ReconSchedulingConfig implements SchedulingConfigurer {

    @Bean
    public DynamicCronTrigger reconCronTrigger() {
        return new DynamicCronTrigger("0/5 * * * * *");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(() -> reconJob.runScanOnce("cron"), reconCronTrigger());
    }
}
```

`configureTasks` 里不用再设置调度器，`SchedulerConfig` 声明的线程池会被框架自动接上。手动触发走另一条更简单的路：任务类的业务方法独立成 `runScanOnce(trigger)` 这样的公开方法，注解方法与 HTTP 探针都调它：

```java
@PostMapping("/trigger/{job}")
public Map<String, Object> trigger(@PathVariable String job) {
    switch (job) {
        case "recon" -> result = reconJob.runScanOnce("manual") + " files";
        case "cancel" -> result = orderJobs.runCancelOnce("manual") + " cancelled";
        case "report" -> result = orderJobs.runReportOnce("manual").toString();
        ...
    }
}
```

调度触发与人工触发共用同一段代码，执行记录里的 trigger 字段区分来源。探针接口顺带返回执行线程名与耗时，我在验证里判断「手动触发跑在 HTTP 线程、调度触发跑在 order-sched 线程」，靠的就是这两个字段。

## 完整案例，跑起来看

以 4 线程池启动，从 HTTP 走一遍完整流程：

```bash
curl -i -X POST "http://localhost:18110/api/orders" \
     -H "Content-Type: application/json" \
     -d '{"orderNo":"SO90001","amount":"199.50"}'
```

返回 201，`created_at` 是 23:53:34.196。超时阈值 15 秒，取消任务每 3 秒扫一轮，18 秒后再查：

![](https://static.xiongneng.me/schedule-order-cancel-journal-20260919235357.png)

状态变成 `CANCELLED`，`cancel_time` 是 23:53:49.669，从下单到取消 15.5 秒，与阈值和轮询间隔的叠加一致。面板里第三块是手动触发的报表统计，`thread` 字段显示 `http-nio-18110-exec-7`：任务代码跑在 HTTP 线程上，人工触发与调度触发共用同一段业务方法，订单数 1、总额 199.50 落进当日报表。

探针面板能一次看清两类运行期证据。poison 的执行记录里 3 条 failed，异常类型与失败线程一一在列；对账扫描的记录里线程名在 `order-sched-2` 与 `order-sched-4` 之间切换，多线程并行取任务的判断直接可查；动态 cron 的 200 响应与非法表达式的 400 problem+json 同框：

![](https://static.xiongneng.me/schedule-probe-panels-20260919235419.png)

两轮对照的关键数字再摆一次。单线程轮：执行线程集合 `{order-sched-1}`，黑名单慢执行 8009 与 8001 毫秒，对账扫描最大间隔 11.5 秒；四线程轮：同样的慢任务照跑，对账扫描最大间隔 5.0 秒，线程集合四个元素。两轮的应用日志 ERROR 都是 0 行。停摆从「听说」变成三个可复查的数：断档次数、最大间隔、线程集合。

执行记录是这套演示的中枢，每条记录五个字段：任务名、触发方式、执行线程、结果、耗时。它由一个容量 400 的内存环形队列承载，HTTP 接口随时吐最近一段，任务行为的变化当场就能对出来。

## 这一篇的测试怎么写

18 个用例全连真实 MySQL，调度相关的断言集中在三类：配置事实、业务行为、异常语义：

```java
@SpringBootTest(properties = "app.scheduler.pool-size=3")
class SchedulerConfigTest {

    @Autowired
    @Qualifier("taskScheduler")
    private TaskScheduler taskScheduler;

    @Test
    void taskSchedulerIsConfigurableThreadPool() {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(scheduler.getPoolSize()).isEqualTo(3);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("order-sched-");
    }
}
```

这个用例把「手工 bean 接管了自动配置」钉在测试里：池大小是自定义属性注入的 3 而非默认的 1，前缀是 `order-sched-`。谁要是把 bean 名字改掉让自动配置偷偷回来，这里第一个失败。

异常语义的用例不打时间的主意，断言全部确定性：

```java
@Test
void manualTriggerOfPoisonJobDoesNot500() throws Exception {
    chaosJobs.setPoison(true);
    try {
        mockMvc.perform(post("/api/scheduler/trigger/poison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.result").value("failed: 演示异常：远端对账接口 503"));
    } finally {
        chaosJobs.setPoison(false);
    }
}
```

故障开着的时候手动触发不抛 500，响应体明确写 failed 与异常消息；开关关掉后同一个任务恢复 no-op。`ErrorHandler` 吞异常的契约单独一个用例，直接调 `handleError` 断言不向外抛。

其余用例的分布：订单接口 4 个（下单 201、校验 400、查询 404、手动触发取消），对账流 5 个（登记 201、重复 409、扫描 DONE、动态 cron 生效与非法 400），报表与慢调用 3 个（聚合正确、未生成 404、慢调用耗时下限），动态 cron 触发器 2 个纯单元测试（改值生效、非法拒绝）。全部用例走 MockMvc 打真实 HTTP 面，测试里操练的路径与生产同一条。

## 避坑指南

**坑一，池大小默认 1，停摆从第一个任务就开始埋着。** 属性类构造器里写死的常数，本地开发任务少看不出问题，任务一多互相拖慢是常态。线上定时任务莫名互相拖延，九成是这个原因。凡是超过一个定时任务的工程，声明自己的 `taskScheduler` 把池大小显式定下来，顺手把线程名前缀和 ErrorHandler 一起配掉。判断有没有踩坑的最快办法：grep 一下日志里的线程名，定时任务的执行线程始终是同一根就是实锤。

**坑二，异常处理指望不上调度注解。** `@Scheduled` 方法抛出的异常默认只有一条框架 ERROR 日志，重试、告警、失败计数框架一概不管。要么在方法里自己兜住并记录，要么挂自定义 `ErrorHandler`。没有自声明线程池时自定义 ErrorHandler 挂不上，这是两条路的硬边界。失败要不要重试，是业务策略，归业务代码或重试框架管，调度器只负责准时叫人。

**坑三，cron 表达式合法不代表语义正确。** 非法表达式启动期就会抛 `IllegalArgumentException`，拦得住；危险的是合法但不符合预期的表达式，6 域写成 7 域的变体、时区理解错位，应用照常启动，任务按错误节奏跑。改完 cron 用探针看一眼下一次触发时间，再上生产。

**坑四，fixedRate 不保证严格节拍。** 单线程池里执行时长超过间隔时不会并发重入，只顺延；错过几个周期就少跑几次。要「执行完再等」用 fixedDelay，要「补跑错过的次数」得在业务侧自己处理，注解层面没有补跑语义。这个坑在「整点必须出数」的报表场景里最疼，节拍型任务单独给一条线程是底线。

**坑五，任务方法必须无参。** 带参数的 `@Scheduled` 方法在启动期直接失败，框架对方法签名的校验比大多数注解严格。参数要么走配置属性注入，要么在方法体里自己读，别把业务参数留在签名上。方法返回值同样被忽略，返回 `Future` 不会让任务变成异步。

**坑六，JdbcClient 的 optionalValue 是单列映射。** 按主键查一整行时用它，多列结果直接抛 `IncorrectResultSetColumnCountException`。查单行走 `listOfRows()` 取第一条，本工程的两个查询服务都是这么写的。我测试就栽在这个方法名上：以为是「可选的一行」，跑起来才知道语义是「可选的一列」。

**坑七，虚拟线程开关会换掉调度器实现。** `spring.threads.virtual.enabled=true` 时 `taskScheduler` 从 `ThreadPoolTaskScheduler` 换成 `SimpleAsyncTaskScheduler`，池大小语义随实现变化。依赖具体实现类的方法之前先确认当前用的是哪一个，测试里 instanceof 断言一遍最稳妥。

**坑八，优雅停机要在 bean 上声明。** `setWaitForTasksToCompleteOnShutdown` 加 `setAwaitTerminationSeconds` 让在跑的任务把当前一轮执行完再退。定时任务写到一半被 kill，落库落在半截，重启后的那一轮会把半截数据再处理一遍，幂等要在业务侧自己保证。取消订单这类天然幂等的任务无所谓，累加型的统计任务必须防重复。

## 小结

定时任务的选型建议可以压成三句话。任务少且不怕串行，默认配置零依赖直接用；只要超过一个定时任务，就手工声明 `taskScheduler`，池大小、线程名前缀、异常处理器三件事一次配齐；要挂自定义 `ErrorHandler` 或精细控制优雅停机，整池接管是唯一完整的路，customizer 只适合轻改。节奏怎么挑看任务性质：清理型用 fixedDelay，节拍型用 fixedRate，要按墙钟对齐就用 cron。

没解决的事也有两件。一是动态调度只覆盖了单实例：多实例部署时改节奏要逐个实例调接口，表达式也没有持久化，重启后回到初始值，这块要么接配置中心，要么配分布式锁，超出这一篇的范围。二是虚拟线程分支我只核实了装配条件，没有做吞吐对比，池大小语义在 `SimpleAsyncTaskScheduler` 上怎么变，等实际用上虚拟线程时我再补测。

## 参考链接

- [Spring Framework Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)：`@Scheduled` 三种计时属性与 `TaskScheduler` 路由的官方说明
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)：`spring.task.scheduling.*` 属性清单与 task 自动配置的行为
- [Spring Framework 7.0 API](https://docs.spring.io/spring-framework/reference/core.html)：`TaskSchedulerRouter`、`Trigger`、`ErrorHandler` 的接口契约
- [spring-boot-autoconfigure 4.1.1](https://central.maven.org/maven2/org/springframework/boot/spring-boot-autoconfigure/4.1.1/)：本文包位置与属性前缀的制品实证来源
