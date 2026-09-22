---
title: SpringBoot4系列25 - 使用内置弹性重试与并发限制
slug: sb4-resilience
date: 2026-09-20 20:40:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Retry, Resilience, ConcurrencyLimit ]
draft: false
---

前段时间我给支付服务对接下游通道，通道偶尔抖一下：前两秒请求全超时，第三秒自己恢复。老处理办法是引 spring-retry 或 Resilience4j，加依赖、加 @EnableRetry、按各自词表写注解，一套下来 POM 多两组坐标，依赖树多两层传递。这次我把 Spring Framework 7 收进框架本体的方案完整跑了一遍：@Retryable 和 @ConcurrencyLimit 两个注解加一个 @EnableResilientMethods 开关，重试与并发限制开箱即用，spring-retry 进入维护模式，Resilience4j 的注解体系在纯重试场景也省了。

这篇文章记录我在 Spring Boot 4.1.1（Spring Framework 7.0.9）上从 jar 取证开始的完整过程：注解在哪个包、有哪些属性、怎么启用，然后把「前两轮失败第三轮成功」的下游抖动和「limit=2 的热点接口」各实测一遍，重试尝试次数、退避耗时、429 拒绝数、方法体并发峰值，全部出自我写的端到端验证脚本，每一轮的原始记录我都留了底，没有手工拼数字。选内置方案，我算的是这笔账：跨团队项目里 spring-retry 的版本漂移与 Resilience4j 的注册表配置各自要维护一套理解，注解直接进 Framework 意味着升级 Spring 就同步升级弹性能力，POM 少两组坐标，依赖树少两层传递，新成员上手只需记住两个注解和一个开关。

## 注解与启用

从 spring-retry 或 Resilience4j 迁移过来，我建议先对照一遍词汇表。spring-retry 的 @Retryable(value = ..., maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2)) 加 @EnableRetry，翻译成 Framework 7 的写法是 @Retryable(includes = ..., maxRetries = 3, delay = 100, multiplier = 2) 加 @EnableResilientMethods。名字换了一轮：value 从异常类型变成了 includes 的别名，maxAttempts 变成 maxRetries 且语义改为额外重试次数，嵌套的 @Backoff 摊平成 delay、jitter、multiplier、maxDelay 四个属性，@Recover 消失。Resilience4j 的 @Retry(name = "x") 与 @RateLimiter 走注册表配置，迁移时把 yml 里的限流参数拆到 limitString 与占位符里。

再把坐标钉下来。我一开始按官方文档的章节路径猜注解在 spring-core，import 直接报错，后来把本地仓库的 spring-context-7.0.9.jar 解开才算实锤：弹性注解在 spring-context 的 org.springframework.resilience.annotation 包，这是解包后的目录实证：

```text
org/springframework/resilience/annotation/Retryable.class
org/springframework/resilience/annotation/ConcurrencyLimit.class
org/springframework/resilience/annotation/EnableResilientMethods.class
org/springframework/resilience/annotation/ConcurrencyLimit$ThrottlePolicy.class
org/springframework/resilience/annotation/RetryAnnotationBeanPostProcessor.class
org/springframework/resilience/annotation/ConcurrencyLimitBeanPostProcessor.class
org/springframework/resilience/retry/MethodRetryEvent.class
```

编程式入口在 spring-core 的 org.springframework.core.retry 包，RetryTemplate、RetryPolicy、RetryListener 都在那里。两个 jar 都由 spring-boot-dependencies 4.1.1 管理，Framework 版本锁 7.0.9，工程里不需要写任何版本号。

依赖只需要一个 webmvc starter，spring-context 与 spring-aop 都在其中：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

一个 starter 两个注解都能用，不用为弹性能力单独引包。

弹性注解的生效机制是 AOP 代理。@EnableResilientMethods 导入 ResilientMethodsConfiguration，注册 RetryAnnotationBeanPostProcessor 与 ConcurrencyLimitBeanPostProcessor，两个类都继承 AbstractBeanFactoryAwareAdvisingPostProcessor，往匹配的 bean 上挂通知器，重试走 SimpleRetryInterceptor，并发限制走 ConcurrencyLimitInterceptor。

我翻了 spring-boot-autoconfigure 的 AutoConfiguration.imports 文件，Boot 4.1.1 没有为弹性能力准备专属 starter，grep 不到任何 Resilience 条目，所以开关要自己声明：

```java
@SpringBootApplication
@EnableResilientMethods
public class ResilienceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResilienceApplication.class, args);
    }
}
```

不加这个开关，@Retryable 与 @ConcurrencyLimit 只是元数据，不会被处理，方法调用一切照旧，而且没有任何报错。我第一次跑就在这里闹了个笑话：注解贴了、服务起了，故意制造失败却发现一次重试都没发生，回头才看到主类上漏了开关。也可以不用总开关，单独声明两个 PostProcessor bean，按需只开重试或只开并发限制。

![](https://static.xiongneng.me/resilience-annotation-components-20260922063404.png)

两个注解都能标在两个位置：方法级只管这一个方法，类型级管这个 bean 层次里所有被代理调用的方法。@EnableResilientMethods 自带两个属性，proxyTargetClass 控制代理是 CGLIB 还是 JDK 接口代理，order 控制这组通知器在代理链上的位置。工程里有事务注解时，重试在事务开启之前还是之后执行由这个 order 决定，重试包在事务外面才能让每次尝试各自开事务，嵌在事务里面则整轮重试共享一个事务，回滚语义完全不同。

对返回 Reactive 类型的方法，@Retryable 同样生效，代理把重试装饰进 Reactor 管线，底层走 Reactor 的 retry 能力，退避参数换算成对应的 FluxInterval 等操作符。同步方法的重试是阻塞式的，调用线程原地等待退避间隔过去；异步方法的重试不占线程，这是同一套注解在两种编程模型下的执行差异。

再确认两个不存在的注解。我把 spring-context 7.0.9 全 jar 内 grep recover，零命中：Framework 7 没有 spring-retry 里 @Recover 的等价物，也没有 @RetryWith 这个名字；重试耗尽后最后一次原始异常直接抛给调用方。降级兜底要靠外层自己接，后面「兜底恢复」一节展开。

## 重试实测

@Retryable 的属性表我从 javap 取证，与 spring-retry 的词汇表差异不小：

| 属性 | 默认值 | 说明 |
|---|---|---|
| value / includes | 空 | 触发重试的异常类型，value 是 includes 的别名，子类与嵌套 cause 都参与匹配 |
| excludes | 空 | 不重试的异常类型 |
| predicate | 默认实现 | MethodRetryPredicate，按 Method 与 Throwable 精细判定 |
| maxRetries | 3 | 最大重试次数，总尝试次数等于 1 + maxRetries |
| timeout | 无超时 | 整轮时间上限，覆盖初试、全部重试与所有退避延迟 |
| delay | 1000 | 首次重试前的等待毫秒数 |
| jitter | 0 | 随机抖动，避免重试风暴齐步走 |
| multiplier | 1.0 | 退避乘数，大于 1 时按指数增长 |
| maxDelay | Long.MAX_VALUE | 退避上限 |
| timeUnit | MILLIS | 时间单位 |

没有 maxAttempts，那个词属于 spring-retry；这里叫 maxRetries，且语义是「额外重试几次」，初试永远先来一次。backoff 也不再是嵌套注解，摊平成 delay、jitter、multiplier、maxDelay 四个属性。每个属性还有对应的 String 变体（maxRetriesString、delayString），支持属性占位符与 SpEL。

业务场景是支付扣款。PaymentService 的 charge 方法标注重试策略，只对通道不可用异常重试，最多 3 次，100ms 起步按 2 倍指数退避、500ms 封顶：

```java
@Retryable(
        includes = ChannelUnavailableException.class,
        maxRetries = 3,
        delay = 100,
        multiplier = 2,
        maxDelay = 500)
public String charge(String orderId) {
    return gateway.call(orderId);
}
```

这个方法签名里没有异常声明，ChannelUnavailableException 是 RuntimeException，代理层捕获后按 includes 匹配决定是否重试。

下游是工程内的模拟通道，arm(failTimes) 之后前 failTimes 次调用抛异常，之后恢复正常，每次调用计入 callCount，callCount 就是代理循环调用的尝试次数：

```java
public void arm(int failTimes) {
    armedFailures.set(Math.max(0, failTimes));
    callCount.set(0);
}

public String call(String orderId) {
    callCount.incrementAndGet();
    int remaining = armedFailures.getAndUpdate(v -> v > 0 ? v - 1 : 0);
    if (remaining > 0) {
        throw new ChannelUnavailableException("支付通道超时, orderId=" + orderId);
    }
    return "txn-" + txSeq.incrementAndGet();
}
```

控制器装填 failTimes 后调用门面，门面负责计时与结果组装。我写的端到端验证脚本跑出的第一组原始记录：

```text
POST /api/payments/charge?orderId=order-1&failTimes=2
{"orderId":"order-1","success":true,"attempts":3,"elapsedMs":323,
 "fallback":false,"message":"txn-1001"}
```

两次失败加一次成功，attempts 是 3，总尝试次数公式 1 + maxRetries 与实测一致。挺有意思的是耗时的构成：前两次失败后各等 100ms 与 200ms 再重试，共 300ms 退避，加上三次调用的开销，323ms 对得上账。

![](https://static.xiongneng.me/resilience-retry-sequence-20260922063408.png)

退避曲线里的 jitter 是防重试风暴的关键参数。下游恢复的瞬间，同一批超时的客户端按同样的 delay 齐步重试，下游刚站起来就被第二波打倒，这是重试风暴的成因。jitter=100 表示每次实际等待在退避值附近随机偏移 100ms 以内，把整齐的重试打散。另一个特殊组合是 delay=0 加正 jitter：multiplier 失效，每次等待直接取 0 到 min(jitter, maxDelay) 之间的随机值，适合「立刻重试但别齐步走」的场景。

includes 与 excludes 只做类型匹配，想按异常细节决策要写自定义谓词。MethodRetryPredicate 接口只有一个 shouldRetry(Method, Throwable) 方法，按方法与异常对象两个维度判断：

```java
@Retryable(
        includes = ChannelUnavailableException.class,
        predicate = ChannelRetryPredicate.class,
        maxRetries = 3)
public String charge(String orderId) {
    return gateway.call(orderId);
}
```

谓词在 includes 与 excludes 之后执行，三者叠加时先过类型筛，再过谓词筛，顺序固定。按异常 message 里是否包含「timeout」决定重试与否，就靠这一层。

把失败配额拉到 5，超过重试能力，我单独跑了一轮看耗尽后的行为：

```text
POST /api/payments/charge?orderId=order-2&failTimes=5
{"orderId":"order-2","success":false,"attempts":4,"elapsedMs":736,
 "fallback":true,"message":"degraded: 支付通道超时, orderId=order-2"}
```

attempts 到 4 就停了，1 次初试加 3 次重试全部失败。736ms 我拆开对过账：退避占 700ms（100 + 200 + 400），多倍乘数被 maxDelay=500 压住前的最后一跳是 400ms。

每次失败 Framework 7 都会发布一个 MethodRetryEvent（继承 MethodFailureEvent，带 getFailure 与 isRetryAborted 两个读法），监听器打一行日志就能看到代理内部的完整过程：

```java
@EventListener
public void onMethodRetry(MethodRetryEvent event) {
    System.out.printf("[retry-event] method=%s, failure=%s, retryAborted=%s%n",
            event.getMethod().getName(), event.getFailure().getClass().getSimpleName(),
            event.isRetryAborted());
}
```

验证脚本的应用日志里收了 12 行 retry-event：两次成功的演示各 2 个失败事件，耗尽的演示 4 个失败事件外加 1 个 retryAborted=true 的中止事件，failure 字段是 RetryException。调用方只能看到最后一次异常，这个事件序列是观测每一次失败的窗口。

不装兜底的裸调用走另一条路：重试耗尽后 ChannelUnavailableException 直接冒出代理，控制器捕获后转成 502 返回：

```text
POST /api/payments/charge-raw?orderId=order-3&failTimes=9  [HTTP 502]
{"error":"ChannelUnavailableException","success":false,"fallback":false,
 "message":"支付通道超时, orderId=order-3"}
```

耗尽后的异常类型就是目标方法抛出的原始类型，代理不做包装，这一点与 spring-retry 的 ExhaustedRetryException 不同。

### 编程式重试

注解管不到的代码块（工具类静态方法、第三方客户端内部、main 方法里的胶水逻辑）走 RetryTemplate。这个类在 spring-core 的 org.springframework.core.retry 包，实例很轻，我在调用点随手建：

```java
var retryPolicy = RetryPolicy.builder()
        .includes(ChannelUnavailableException.class)
        .maxRetries(3)
        .delay(Duration.ofMillis(100))
        .multiplier(2)
        .maxDelay(Duration.ofMillis(500))
        .build();

String txn = new RetryTemplate(retryPolicy).invoke(
        () -> flakyGateway.call(orderId));
```

invoke 变体的行为与 @Retryable 一致：耗尽后把最后一次原始异常抛给调用方。execute 变体多一层信息，抛出的 RetryException 带 getExceptions() 与 getLastException()，整轮所有失败都拿得到：

```java
try {
    String txn = retryTemplate.execute(() -> flakyGateway.call(orderId));
} catch (RetryException ex) {
    // ex.getExceptions() 是这一轮全部失败的列表
    log.warn("扣款失败 {} 次, 最后一次: {}", ex.getExceptions().size(),
            ex.getLastException().getMessage());
}
```

RetryListener 回调挂在 template 上，能收到每轮执行的开始、每次失败与耗尽三种时刻，适合把编程式重试的观测接进日志或指标。注解路径的事件叫 MethodRetryEvent，编程式路径的回调叫 RetryListener，两套观测口名字不同、位置不同，按场景各取所需。

### 中止信号

耗尽之外还有两种提前结束：timeout 到点，或者线程被中断。这两条路径的对外表现与自然耗尽一致，最后一次原始异常照常抛出；差异在事件流，isRetryAborted 为 true 的 MethodRetryEvent 会先于异常发出，failure 字段带 RetryException。timeout 属性按整轮计时（从第一次调用开始算），不是按单次尝试计时，delay 乘 multiplier 的退避曲线必须和 timeout 一起规划，否则 400ms 封顶的三连退避可能撑不到第三次重试就被 timeout 掐断。

## 并发限制实测

@ConcurrencyLimit 的属性表短得多：limit（value 是它的别名）、limitString（占位符与 SpEL）、policy。常用的就这三样，其余交给默认值。policy 取两个值，BLOCK 是默认值，满员后后来的线程排队等待；REJECT 满员后立刻抛 InvocationRejectedException，这个异常继承 java.util.concurrent.RejectedExecutionException，语义与线程池拒绝任务一致。

热点接口 limit=2 加 REJECT 策略，方法体固定耗时 200ms：

```java
@ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
public String rejectReport(int seq) {
    return enterBody(seq, 200);
}
```

控制器把 InvocationRejectedException 转成 HTTP 429，并维护接受与拒绝两个计数器。验证脚本用 6 个后台 curl 同时打这个接口，我拿到手的原始记录长这样：

```text
seq=2 HTTP 429 0.005622s
seq=5 HTTP 429 0.004018s
seq=0 HTTP 200 0.220105s
seq=1 HTTP 200 0.205970s
seq=3 HTTP 200 0.269292s
seq=4 HTTP 200 0.214764s
GET /api/hotspot/stats
{"rejected":2,"accepted":8,"maxInFlight":2}
```

6 个请求里 2 个被拒，4 个通过。被拒的请求 4 到 6 毫秒就返回了，拒绝发生在进入方法体之前，等待成本接近零；通过的 4 个请求各自耗时 200ms 出头，分成两批串行过闸。maxInFlight 记录方法体内的并发峰值，全程没超过 2，limit 的约束成立。

![](https://static.xiongneng.me/resilience-concurrency-semantics-20260922063412.png)

429 的选择也过一遍。InvocationRejectedException 映射成 HTTP 429 Too Many Requests 还是 503 Service Unavailable，决定的是客户端行为约定：429 配 Retry-After 头，客户端按服务端给的节奏退避；503 表达服务整体不可用。并发限流属于前者，工程里在 429 响应体里带上 reason 字段，前端据此展示「当前排队人数多，稍后再试」而不是「系统故障」。

BLOCK 策略换一个接口，同样 limit=2：

```java
@ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.BLOCK)
public String blockReport(int seq) {
    return enterBody(seq, 200);
}
```

4 个并发请求全部成功，总耗时 833ms。200ms 的任务分成两批过闸，第二批等第一批让坑，谁都没有被牺牲。两种策略的选型我的判断是：前台交互接口用 REJECT，快速失败把压力交回调用方；内部批处理或必须成功的调用用 BLOCK，代价是排队线程占着线程池的坑位。

官方文档给了 @ConcurrencyLimit 一个特殊场景：虚拟线程。虚拟线程没有池上限，方法内部一旦同步等待，平台线程可能被批量占满，@ConcurrencyLimit(1) 等价于给 bean 实例上一把锁，在 Boot 4 的 spring.threads.virtual.enabled=true 环境下是少见但管用的保险。

这个注解的前身挺有意思：Framework 1.0 时代就有 ConcurrencyThrottleInterceptor，一个可以手工挂进 AOP 代理链的并发节流拦截器，语义与今天的 @ConcurrencyLimit 相同。Framework 7 做的事是把它注解化，声明成本从「装配 ProxyFactoryBean」降到「贴一行注解」。

limit 的动态化用 limitString，支持属性占位符与 SpEL：

```java
@ConcurrencyLimit(limitString = "${payment.hotspot.limit:2}")
public String blockReport(int seq) {
    return enterBody(seq, 200);
}
```

值在代理创建时解析一次，运行期不重读注解。配置中心改了 payment.hotspot.limit，要等 bean 重建才生效，热更新场景按这个时机做预期管理。

并发压测的方法也值得展开。我的验证脚本用 6 个后台 curl 同时发请求，Shell 层面就是 `curl ... &` 六连加一次 wait，每个请求拿到各自的 HTTP 状态码与 time_total。这个压法没有专业压测工具的吞吐控制，但复现并发重叠足够：方法体 200ms，6 个请求在 20ms 内全部发出，重叠窗口远大于闸门宽度，429 必然出现。自动化断言用同样的思路写进了测试，ExecutorService 固定 6 线程，CountDownLatch 做起跑闸，收集状态码列表后断言 accepted 加 rejected 等于总数、maxInFlight 不超过 2。

## 兜底恢复

spring-retry 有一套 @Recover：重试耗尽后自动路由到同签名加 @Recover 的方法。Framework 7 没有这个机制，耗尽后异常原样抛出，降级要靠调用方显式处理。我在工程里加了一层门面，把重试调用与降级收在同一个方法里：

```java
public ChargeResult chargeWithFallback(String orderId, int failTimes) {
    gateway.arm(failTimes);
    long t0 = System.nanoTime();
    try {
        String txn = paymentService.charge(orderId);
        return new ChargeResult(orderId, true, gateway.getCallCount(),
                elapsedMs(t0), false, txn);
    } catch (ChannelUnavailableException ex) {
        return new ChargeResult(orderId, false, gateway.getCallCount(),
                elapsedMs(t0), true, "degraded: " + ex.getMessage());
    }
}
```

catch 里返回降级的 ChargeResult，调用方拿到统一的结果对象，fallback 字段标记是否走了兜底。这个写法比 @Recover 多三行，但降级逻辑显式可见，不需要翻注解才知道耗尽后去哪。

兜底返回什么，按接口语义分三档：查询类接口回缓存里的旧值，多带一个 fetchedAt 时间戳让前端展示「数据非实时」；写操作回「已受理，稍后补偿」，把请求落进本地表由定时任务补投；没有替代品的操作直接抛出，让调用方收到明确失败，别为了不抛异常编一个假的返回值。兜底值与正常值混在同一个返回类型里时，标记字段不可省略，排查问题时「这笔单据是降级产物」这一条信息值一次复盘。

降级与重试的分工也在这个方法里：重试负责可恢复的瞬时故障，降级负责重试救不回来的持续故障，两道防线各管一段，耗尽时间点就是分界线。这段代码里藏着一个代理陷阱：charge 必须从另一个 bean 的引用调用。PaymentFacade 注入 PaymentService，注入进来的是代理对象，重试拦截器在代理层生效；假如 PaymentService 自己的方法内部 this.charge()，this 是原始对象，绕过代理，重试一次都不会发生。这也是「重试标注在哪个 bean、从哪里调用」值得在 code review 里盯一眼的原因。

方法级的兜底还有一种组合：RetryTemplate（org.springframework.core.retry 包）编程式重试，execute 变体抛出的 RetryException 带 getExceptions() 与 getLastException()，比注解的「只见最后一次异常」多一层信息，适合需要在降级时区分失败原因的场景。

## 完整案例

工程 springboot4-resilience 的结构：

```text
com.xncoding.resilience
├── ResilienceApplication          # @SpringBootApplication + @EnableResilientMethods
├── controller
│   ├── PaymentController          # /api/payments 重试演示接口组
│   └── HotspotController          # /api/hotspot 并发限制演示接口组
├── service
│   ├── FlakyPaymentGateway        # 模拟抖动下游，arm 控制失败配额
│   ├── PaymentService             # @Retryable 标注点
│   ├── PaymentFacade              # 计时、降级兜底门面
│   └── HotspotService             # @ConcurrencyLimit 两个策略各一个方法
├── listener
│   └── RetryEventLogger           # MethodRetryEvent 监听
├── domain
│   └── ChargeResult               # 尝试次数/耗时/是否降级的结果单
└── exception
    └── ChannelUnavailableException
```

测试覆盖五条主链路，5 个用例全部通过：

| 用例 | 断言 |
|---|---|
| failTimes=2 重试后成功 | success=true、attempts=3、fallback=false、2 个失败事件 |
| failTimes=5 耗尽走兜底 | success=false、attempts=4、fallback=true、4 失败事件 + 1 中止事件 |
| 裸调用耗尽冒泡 | HTTP 502、error 为 ChannelUnavailableException、rawAborts=1 |
| REJECT 并发 6 请求 | 出现 429、accepted+rejected=6、maxInFlight=2 |
| BLOCK 并发 4 请求 | 全部 200、maxInFlight=2 |

测试里最能说明事件机制的是这条断言：RETRY_EVENTS 列表在测试上下文里收集 MethodRetryEvent，重试成功的用例收到 2 个失败事件、耗尽的用例收到 4 个失败事件加 1 个中止事件，事件里能直接拿到 isRetryAborted 与 failure 的类型。并发用例我没用 RestTestClient，用 JDK 的 HttpClient 加线程池驱动真并发，CountDownLatch 保证 6 个请求同一毫秒起跑：

```java
List<Future<String>> futures = new ArrayList<>();
for (int i = 0; i < total; i++) {
    futures.add(pool.submit(() -> {
        gate.await();
        return http("POST", path + "?seq=" + seq).split("\\|", 2)[0];
    }));
}
gate.countDown();
```

验证脚本一轮跑完全部场景，我把打出来的原始记录整段留了底：

```text
POST /api/payments/charge?orderId=order-1&failTimes=2
attempts=3 elapsedMs=323 success=true
POST /api/payments/charge?orderId=order-2&failTimes=5
attempts=4 elapsedMs=736 fallback=true
GET /api/hotspot/stats
accepted=8 rejected=2 maxInFlight=2
```

一轮端到端跑下来的汇总我记在这里：重试成功 323ms、耗尽兜底 736ms、裸调用 502、REJECT 2 个 429、BLOCK 总耗时 833ms、热点统计 accepted=8 / rejected=2 / maxInFlight=2、retry-event 12 行、ERROR 0 行、WARN 0 行。日志里的 retry-event 行是预期内的重试记录，属于这套机制的正常输出，我特意核对过，不是告警。

![](https://static.xiongneng.me/resilience-verify-panel.png)

脚本本身不到一百行，核心结构三段：起服务、取数、关服务。起服务用 java -jar 拉后台进程，循环 curl 一个轻接口等就绪；取数就是前面那些 curl 加 6 连发压；收尾 kill 进程后打印全部结果。脚本开头显式 export SERVER_PORT=18250，因为 CI 环境会注入自己的 SERVER_PORT，不固定下来端口断言全部落空。这套脚本与单元测试互补：测试在随机端口验证行为正确性，脚本在固定端口复现一次完整操作路径，正文引用的每个数字都出自同一份脚本一口气打出来的原始记录。

## 避坑指南

**坑一，注解包名在 spring-context 不在 spring-core。** 官方文档章节挂在 core/resilience.html，@Retryable 等注解的实体却在 spring-context 的 org.springframework.resilience.annotation 包，RetryTemplate 才在 spring-core 的 org.springframework.core.retry。我按文档路径猜包名，import 当场失败。

**坑二，忘了 @EnableResilientMethods 注解静默失效。** 没有开关时注解只是元数据，方法照常执行，重试与限流一次都不会发生，且没有任何报错。判断代理是否生效，最直接的办法是看注入的对象类型里有没有 CGLIB 标记，或者干脆制造一次失败数一数尝试次数。

**坑三，maxRetries 是额外重试次数，总次数要加一。** maxRetries=3 的方法最多被执行 4 次。从 spring-retry 迁移时按 maxAttempts 的记忆填数，重试次数悄悄少一次。

**坑四，同类内部调用绕过代理。** @Retryable 方法被同一个类的 this 调用时，重试拦截器不在场。门面模式或自注入（ObjectProvider 拿自己）都能解决，我的工程用 PaymentFacade 隔离调用方。

**坑五，Framework 7 没有 @Recover。** spring-retry 的耗尽自动路由在原地不存在，耗尽后最后一次原始异常直接抛出。降级兜底要么调用方 try/catch，要么换 RetryTemplate 的 execute 拿完整的失败列表。

**坑六，耗尽异常不被包装。** 调用方 catch 的是目标方法声明的原始异常类型（ChannelUnavailableException），catch ExhaustedRetryException 会落空。跨场景写通用异常处理时按原始类型匹配。

**坑七，REJECT 与 BLOCK 的选择影响线程池。** REJECT 快速失败不占资源，BLOCK 排队线程都挂着等坑。BLOCK 限流值设得比线程池大几倍的场景下，高峰期所有工作线程都可能堵在排队上，连带无关接口一起饿死。

**坑八，@ConcurrencyLimit 的限流是 per bean 实例。** 限流状态挂在代理实例上，多实例部署时每个 JVM 各限各的，全局并发是实例数乘 limit。容量规划按这个乘法算。

**坑九，重试要带上幂等前提。** 下游收到请求但响应超时，重试就是二次扣款。includes 里只放可安全重试的异常，写操作先做幂等键，注解管不住业务语义。

**坑十，退避参数直接决定线程占用。** 阻塞式重试期间调用线程原地等待，delay=1000 multiplier=2 的三连击要空等 7 秒。Web 请求链路上大延迟退避会把 Tomcat 线程耗光，同步接口的退避上限要按线程池容量倒推，或改用 reactive 返回类型让 Reactor 的 retry 接管。

**坑十一，MethodRetryEvent 只在注解路径发布。** @Retryable 的每次失败都有事件，RetryTemplate 走的是 RetryListener 回调，两套观测口不通用。混用时监听器各配各的。

**坑十二，String 变体属性才能用配置中心热调。** maxRetries 是 long 字面量，改一次要重新编译；maxRetriesString 与 limitString 支持 ${...} 占位符，运行时从 Environment 解析，限流值做成配置项要用这个变体。

**坑十三，maxRetries 配 0 表示一次都不重试。** builder 与注解都接受 0，语义是只执行初试，出错直接抛。这个值适合做环境的降级开关：测试环境把重试关掉，失败路径的用例不用等退避时钟走完。

**坑十四，InvocationRejectedException 继承 RejectedExecutionException。** catch RejectedExecutionException 的通用处理器会把并发限流的拒绝一并接走，@Async 提交链路里它会被当成线程池拒绝处理。区分两种来源要么 catch 子类先于父类，要么在日志字段里补方法名。

**坑十五，BLOCK 的排队过程没有观测点。** REJECT 有异常有计数器，BLOCK 的等待线程卡在代理层，没有事件也不进任何指标，排队时间只能从接口耗时里反推。要观测排队长度，得自己在方法入口出口埋 inFlight 计数，我的 HotspotService 里 maxInFlight 就是这个用法。

## 小结

Framework 7 把重试与并发限制做成了框架本体的两个注解：@Retryable 管「失败了再试几次、隔多久再试」，@ConcurrencyLimit 管「同一时刻最多几个人进方法体」，@EnableResilientMethods 一开就生效，依赖就是工程里已有的 spring-context 加 spring-aop。

选型建议按场景走：存量工程已经在用 spring-retry 或 Resilience4j 且用得深，不必为迁移而迁移，等自然升级窗口再换；新工程或想给依赖树减负的，直接上内置注解，迁移时记住三件事，词汇表换了（maxRetries 与 includes，没有 maxAttempts 与 retryFor）、@Recover 不存在（兜底自己写门面）、限流是单实例语义（全局容量按实例数乘 limit 算）。写操作接重试之前先把幂等键做了，这个前提注解帮不了你。

没解决的事也有两件。一是 BLOCK 策略的排队过程依然没有观测点，排队长度只能自己埋计数，要不要在工程里补一个通用的 inFlight 切面，我还没想好。二是 Reactive 路径的重试我只确认了机制（代理把重试装饰进 Reactor 管线），退避在事件循环上的实际表现没有实测数字，这块留到我补齐 reactive 的验证场景再展开。

## 参考链接

- [Spring Framework 7.0 Resilience Features](https://docs.spring.io/spring-framework/reference/core/resilience.html)：@Retryable、@ConcurrencyLimit 与 RetryTemplate 官方章节
- [@Retryable javadoc（7.0.9）](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/resilience/annotation/Retryable.html)：注解属性与默认值的 API 定义
- [Spring Boot 4.1 Reference](https://docs.spring.io/spring-boot/4.1/reference/)：Boot 4.1 配套文档，弹性注解无自动配置的出处
- [spring-context 7.0.9（Maven Central）](https://repo1.maven.org/maven2/org/springframework/spring-context/7.0.9/)：本文注解包名的制品实证来源
