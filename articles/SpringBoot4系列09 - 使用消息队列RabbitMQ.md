---
title: SpringBoot4系列09 - 使用消息队列RabbitMQ
slug: sb4-rabbitmq
date: 2026-10-07 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, RabbitMQ, AMQP, 消息队列 ]
draft: false
---

上一篇讲缓存，结尾留了个尾巴：缓存解决的是「读得快」，系统里另一半问题「通知得可靠」还空着，这一篇来补。场景还是订单。订单支付成功之后要发短信、要推邮件、要给积分系统记账，这几件事没有一件是下单主链路必须等着的，也没有一件是挂了能装作没看见的。

这个坑我早年在一家电商公司结结实实踩过一回。支付成功的方法里挨个调用短信服务、邮件服务、积分服务，功能是对的，上线第一周就出事了。短信服务商那边抖了三十秒，支付接口跟着超时了三十秒，用户盯着转圈的进度条以为支付失败，又付了一次。投诉进来我翻日志，支付服务自己一行错误都没有，它在等一个跟它不相干的下游。后来我把通知改成异步：订单主链路只做一件事，把「支付成功」这件事喊一嗓子，谁关心谁去听。喊完就返回，短信晚到十秒用户无感；短信服务挂了，消息在队列里排队，等它缓过来接着发。主链路的生死从此不跟下游绑在一起。

有人会问，为什么不用更轻的进程内事件总线。我踩过的坑给的答案是：进程内的方案解决不了「下游服务挂了」的问题，订阅者和发布者在同一个 JVM 里，进程一挂大家一起没，进程内的异步只是把超时换成了丢事件。消息队列的价值就是把这件事搬到进程外，broker 的持久化替你扛住下游的宕机窗口，服务重启的间隙消息照样在。

![](https://static.xiongneng.me/rabbitmq-message-lifecycle-20260922005534.png)

干这件事的基础设施，这一篇我选 RabbitMQ。不是因为它新，恰恰是因为它老，老到所有的坑前人都踩过了，老到 Spring 生态里那套 spring-amqp 的抽象成熟得几乎不用操心协议层。说实话我看重的就是这份成熟。Spring Boot 4 这边 starter 极简化，自动配置独立成模块，yml 开关照常好使。整篇里有三处细节我打算单独展开：一个在消息转换器里，一个在重试机制里，还有一个是发送方确认的定制口子。4.1.1 的制品我翻过一遍，要点先列在这儿，后面各自展开。

配套工程在 `springboot4-rabbitmq` 目录，场景是订单事件的发布与消费，拓扑里带完整的死信兜底。六个测试用例我全连着真实 RabbitMQ 4.3.6 跑，broker 在我树莓派的 Docker 里，不是内嵌的内存替身。超时、重试、死信这些时序敏感的行为，只有真 broker 才作数。

工程很小，一个 REST 服务加两个监听器。`POST /api/orders` 创建订单，`POST /api/orders/{orderNo}/pay` 支付并发布 ORDER_PAID 事件，`GET /api/notify/state` 是观测口，吐出 published、consumed、retried、deadLettered 四个计数和最后一次发送方确认的结果。为了做实验我还埋了两个后门：一个能对指定订单注入一次消费故障，一个能发布一条永远消费失败的 POISON 事件。观测归观测，注入归注入，计数才干净。

## 4.1.1 里 AMQP 的几个要点

### starter 极简化

先看依赖。`spring-boot-starter-amqp` 的 POM 我拆开数过，就两层

```text
spring-boot-starter-amqp
├── spring-boot-starter
└── spring-boot-amqp            ← 自动配置本体
    ├── spring-boot
    ├── spring-messaging
    ├── spring-rabbit           ← Spring AMQP 的实现主体（4.1.1）
    │   └── amqp-client         ← RabbitMQ 的 Java 客户端（5.30.0）
    └── spring-boot-transaction
```

跟缓存篇的 starter-cache 一个套路：starter 本身不带任何中间件客户端，客户端和实现都收进 `spring-boot-amqp` 模块里。BOM 里登记的版本我逐个数了一遍：spring-amqp 4.1.1、amqp-client 5.30.0、rabbit-stream-client 1.6.0。这些数字不用背，需要的时候去 BOM 里查就行，我是写这篇的时候特意对过一遍，怕文章里的版本和仓库里的对不上。

![](https://static.xiongneng.me/rabbitmq-starter-anatomy-20260922005534.png)

还有一个跟 Redis 篇不一样的点我单独说。Redis starter 也不带序列化器，但 Redis 的 JSON 序列化要显式处理类型信息；AMQP 这边协议本身就带 `__TypeId__` 头，类型映射是框架的事，要操心的只剩「信任哪些包」。这个区别讲转换器的时候我会展开。

### 自动配置在 amqp 模块

包名规律跟 MongoDB、Redis、缓存三篇完全一致，RabbitMQ 的自动配置在独立模块里，包名是

```text
org.springframework.boot.amqp.autoconfigure
```

这个模块 47 个类，自动配置注册文件里有三条记录，`RabbitAutoConfiguration`、健康检查、metrics 各一条。触发条件是 `@ConditionalOnClass({RabbitTemplate.class, Channel.class})`，classpath 上有 spring-rabbit 就装配；broker 连不上只影响运行，不影响启动，应用照常起来。这个行为我特意把 broker 停掉试过，启动日志干干净净。类名是 `RabbitAutoConfiguration`，检索资料按这个名字走就行。

这个自动配置类我翻了内容，装配的东西不少：连接工厂、`RabbitTemplate`、`RabbitAdmin`、监听容器工厂，一条链全齐。连接工厂默认就是带缓存的实现，常规用量不必另配；要调连接参数，同样走定制口子，思路跟后面的 `RabbitTemplateCustomizer` 一致。整条链唯一需要我动手的就是那一个转换器 Bean。

### 属性前缀 spring.rabbitmq

前缀我从 `RabbitProperties` 的字节码常量池里确认过，是 `spring.rabbitmq`。对比 Redis 的 `spring.data.redis.*`（多了个 data）和缓存的 `spring.cache.*`，AMQP 属于前缀原样保留的那一类。常用的几组属性我直接贴工程里这份

属性不配会怎样我也试过：host 不给就落到默认的 localhost，应用照样启动，发消息连不上才报错。所以连不上 broker 的问题都发生在运行期，排查的时候别光盯着启动日志。

```yaml
spring:
  rabbitmq:
    host: 192.168.1.97
    port: 5672
    username: admin
    password: admin123456
    virtual-host: /
    publisher-confirm-type: correlated   # 发送方确认
    publisher-returns: true              # 路由失败回执
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 500ms
          multiplier: 1.5
```

`publisher-confirm-type` 和 `publisher-returns` 这两个发送方可靠性开关都在，`listener.simple.retry.*` 这一组重试配置照用。底层重试机制是另一回事，它跟你想的可能不太一样，放在核心代码里细说。

这份 yaml 我按组拆开讲。`host/port/username/password/virtual-host` 是连接五件套，broker 在我树莓派上，virtual-host 用默认的根路径。`publisher-confirm-type: correlated` 让 broker 对每条消息回执确认，correlated 模式下回执带关联数据，能把确认结果跟具体那次发送对上号。`publisher-returns: true` 打开路由失败回执。`listener.simple.retry.*` 四个参数凑成一个完整的重试策略：开关、次数上限、初始间隔、退避倍率，少一个都不完整。

### 消息转换器，Jackson 2/3 双轨

spring-amqp 4.1.1 的 jar 我解开数过，`org.springframework.amqp.support.converter` 包里两套 Jackson 转换器并存

| Jackson 2（旧，com.fasterxml） | Jackson 3（新，tools.jackson） |
|---|---|
| `Jackson2JsonMessageConverter` | `JacksonJsonMessageConverter` |
| `Jackson2XmlMessageConverter` | `JacksonXmlMessageConverter` |
| `DefaultJackson2JavaTypeMapper` | `DefaultJacksonJavaTypeMapper` |

跟 Redis 篇序列化器的双轨一个形态：旧的留着，新的用 Jackson 3 的 `tools.jackson.databind.ObjectMapper`。新转换器的签名我在字节码里确认过，构造方法可以直接传 `JsonMapper`，也可以传一个可变参数的信任包列表。

web 工程自带 Jackson 3，所以这一篇我的结论很干脆，**新工程用 JacksonJsonMessageConverter，别用 Jackson2JsonMessageConverter。**后者照样能跑，但会把 com.fasterxml 的依赖拽进来，两套 Jackson 同 classpath 的日子，能避就避。

选型的判断依据我展开一下。`__TypeId__` 头的映射行为两套转换器都一样，差别在底下的 ObjectMapper：Jackson 3 的 `ObjectMapper` 在 Boot 4 的 web 栈里就是默认那个，消息体序列化出来的 JSON 风格和 HTTP 接口返回的完全一致，排查问题的时候两边可以对着看。这是我坚持选 Jackson 3 转换器最实际的理由，不只是为了避开重复依赖。

### AMQP 有专属测试 starter

缓存篇的规律在 AMQP 这里也成立。`spring-boot-starter-amqp-test` 在 4.1.1 的 BOM 里登记着，组成是 starter-amqp 加 `spring-boot-starter-test` 加 `spring-rabbit-test`，一行引入。这一篇的测试就用的它，省了我自己凑依赖的工夫。

## 依赖和配置

pom 全家福，Web 和校验照旧，AMQP 一个 starter，测试两个

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
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<!-- 专属测试 starter = starter-amqp + starter-test + spring-rabbit-test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

没有 Jackson 依赖。JSON 转换器用的 Jackson 3 由 web starter 顺路带进来，web 工程里 AMQP 的 JSON 化是零额外依赖的。我第一次列依赖清单的时候还犹豫要不要单独加 jackson，翻了依赖树发现早就有了，白犹豫一场。

## 核心代码

### 拓扑声明，Bean 写好，剩下的交给 RabbitAdmin

消息队列的第一道坎不是代码，是概念。交换机、队列、绑定，三个东西各管各的。我的记法：交换机是邮局分拣机，队列是信箱，绑定写的是「什么样的信进哪个信箱」。生产者永远只面对交换机，不需要知道信箱在哪。

这套拓扑在 Spring 里全部用 Bean 声明

```java
@Configuration
public class RabbitTopologyConfig {

    public static final String ORDER_EXCHANGE = "sb4.order.exchange";
    public static final String QUEUE_NOTIFY = "sb4.order.notify";
    public static final String DLX_EXCHANGE = "sb4.order.dlx";
    public static final String QUEUE_DEAD = "sb4.order.dead";
    public static final String RK_ORDER_CREATED = "order.created";
    public static final String RK_ORDER_PAID = "order.paid";
    public static final String RK_ORDER_DEAD = "order.dead";

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFY)
                .deadLetterExchange(DLX_EXCHANGE)      // 挂死信参数的关键一行
                .deadLetterRoutingKey(RK_ORDER_DEAD)
                .build();
    }

    @Bean
    public Binding notifyBinding() {
        // order.* 同时匹配 order.created 与 order.paid
        return BindingBuilder.bind(notifyQueue()).to(orderExchange()).with("order.*");
    }

    // 死信交换机、死信队列、绑定同理，DirectExchange + order.dead
}
```

![](https://static.xiongneng.me/rabbitmq-topology-20260922005534.png)

这些声明不需要手动执行。自动配置好的 `RabbitAdmin` 会在连接建立之后，把容器里所有的交换机、队列、绑定 Bean 统一向 broker 声明一遍。声明是幂等的，已经存在的不会重建，我重启过好几轮，从没见过重复声明报错。拓扑跟着应用走，换一个环境部署，拓扑自动就位，挺省心。

我第一次用的时候犯过嘀咕：应用起来之前，broker 上是不是得先手工把队列建好？不用。开发环境里我随时可以把 broker 上的队列删干净，应用一连上，交换机、队列、绑定全体重新就位。拓扑声明因此成了代码的一部分，它跟着 jar 走，不跟着环境走，换机器部署省掉了手工建队列的仪式，也不会出现「我本地有这个队列，线上没有」的悬案。

业务队列上挂的 `x-dead-letter-exchange` 参数是死信机制的地基，`QueueBuilder` 的 `deadLetterExchange` 方法就是给它准备的便捷入口。这一行的意思是，凡是进了这个队列之后被「拒收且不回队」的消息，broker 自动转发到指定的死信交换机去。这个语义先记住，后面讲重试的时候它是最后一环。

routing key 的设计我多说两句。业务队列绑的是 `order.*`，一个通配符同时接住 `order.created` 和 `order.paid`，以后要加 `order.refunded`，事件照发，绑定不用动。死信那边是 `DirectExchange`，routing key 写死 `order.dead`，一一对应，不存在歧义。两个交换机两种类型，各取所需：业务侧要扩展性，死信侧要确定性。

拓扑 Bean 我全声明成 durable，broker 重启之后拓扑还在。这跟消息持久化是两码事，消息要不要落盘由发送端的投递模式决定，spring-rabbit 发消息默认就是持久化的，两边凑齐，重启树莓派上的 broker 我验证过，队列和消息都还在。

### 消息转换器，一个 Bean，两端生效

发送和消费都要 JSON，机制藏在 `RabbitAutoConfiguration` 内部的 `RabbitTemplateConfiguration`：它拿着一个 `ObjectProvider<MessageConverter>`，只要容器里存在**唯一**的 `MessageConverter` Bean，就自动把这个转换器 set 进 `RabbitTemplate`，监听容器工厂同样处理。

所以整个配置就一个 Bean，**机制上发送端和消费端一起生效**

```java
@Configuration
public class RabbitClientConfig {

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        // 信任包前缀：只允许反序列化自己工程的类型（对应 __TypeId__ 头）
        return new JacksonJsonMessageConverter("com.xncoding.");
    }
}
```

没有手动 new RabbitTemplate，没有在 `@RabbitListener` 上指定 containerFactory，两端全是自动的。发送时 `OrderEvent` 对象被序列化成 JSON 字节写进消息体，同时挂上 `__TypeId__` 头；消费时容器拿着这个头反查类型，反序列化好再把对象递给监听方法。监听方法签名里写的就是领域对象，不是原始字节，这是我特别喜欢这套抽象的地方。

`OrderEvent` 里就几个字段，订单号、事件类型、事件 ID、时间戳，都是字符串和整型，Jackson 序列化毫无悬念。比较容易疏忽的是字段演进：异步链路两端的部署是错开的，消费端可能还跑着老结构。加字段没问题，改字段语义要慎重，这是异步化之后必须养成的习惯。

那个信任包参数我多说一句。`__TypeId__` 头里写的是全限定类名，消费端会照着它反序列化。默认情况下这个映射是放行的，意味着谁往队列里塞一条 `__TypeId__` 指向恶意类的消息，理论上是能被反序列化的。构造参数里传 `"com.xncoding."` 之后，只有这个前缀下的类型能通过，攻击面就收住了。**成本是一个单词，收益是一类漏洞，怎么算都划算。**

### 发送方确认，RabbitTemplateCustomizer 这个口子

yml 里开了 `publisher-confirm-type: correlated` 之后，broker 会为每条消息回执确认。回调往哪挂，这里我闹过笑话：给 `RabbitTemplate` 写个 `@Bean` 自己 new 一个，等于把自动配置的模板整个顶掉，确认回调悄悄就失灵了。正确姿势是自动配置留的定制口子

```java
@Bean
public RabbitTemplateCustomizer reliabilityCustomizer() {
    return template -> {
        template.setConfirmCallback((correlationData, ack, cause) ->
                lastConfirmResult = ack ? "ACK" : "NACK: " + cause);
        template.setReturnsCallback(returned ->
                lastConfirmResult = "RETURNED: " + returned.getReplyText());
    };
}
```

自动配置在创建 `RabbitTemplate` 之后，会把容器里所有的 `RabbitTemplateCustomizer` 依次执行一遍，只改关心的属性，其余默认值一个不动。这个类在自动配置包 `org.springframework.boot.amqp.autoconfigure` 里。这里还有个坑：IDE 会提示 `org.springframework.amqp.rabbit.core` 里有一个同名的类，import 到那个，编译直接报找不到符号，自动配置不认它。两个同名类分属两个包，这个形态在缓存篇的 `RedisCacheConfiguration` 已经见过一次，第二次撞上我就没再犹豫。

confirm 和 return 的分工记一句话就够，**confirm 管「消息到没到 broker」，return 管「到了 broker 但路由不到任何队列」。**两个都开了，发送端可靠性才算配齐。

这段代码里的 `lastConfirmResult` 是个简单的字符串变量，demo 里专门用来观测确认结果，生产上更常见的做法是打日志加监控埋点。形态不重要，重要的是两个回调都挂在定制口子里，不碰自动配置的模板，升级 Boot 也不用跟着改。

return 回调平时安静得很，它只在消息到了 broker 却路由不到任何队列的时候才说话。我把 routing key 改成一个不存在的值试了一回，RETURNED 立刻出现在统计面板里，replyText 把原因写得明明白白。这类问题要没有 return 回调，消息就悄无声息地消失在 broker 里，日志里什么都查不到，这也是我坚持两个开关都要开的原因。

### 消费端一个 @RabbitListener 就够

消费端比发送端还省事，一个注解一个方法

```java
@RabbitListener(queues = RabbitTopologyConfig.QUEUE_NOTIFY)
public void onOrderEvent(OrderEvent event) {
    notificationService.onDeliveryAttempt(event);
    // ... 业务校验，失败抛异常
    doNotify(event);
    notificationService.onConsumed(event);
}

@RabbitListener(queues = RabbitTopologyConfig.QUEUE_DEAD)
public void onDeadLetter(OrderEvent event) {
    notificationService.onDeadLettered(event);
    log.error("[死信兜底] 事件进入死信队列，待人工处理: orderNo={}", event.getOrderNo());
}
```

两个监听方法，一个管正常业务，一个管死信兜底。`acknowledge-mode` 用默认的 auto，方法正常返回自动 ack，抛异常自动 nack，心智负担最小。手动 ack 那套方案我想了一下就放下了，能交给框架的就不自己攥在手里。

监听容器默认单消费者。通知这种场景吞吐要求不高，默认配置就够；要提并发，yml 里 `listener.simple.concurrency` 一行的事。我保持默认的原因是演示工程要观测计数，多消费者并发会让 attempts 的时序变得难读，先保可读，再谈吞吐。

### 重试，引擎是 spring-core 的 RetryTemplate

这一篇最重要的实测发现在这里。消费失败分两类：网络抖一下、下游服务抖一下的偶发失败，直接进死信太浪费，先重试几次是标准动作。yml 里用的是 `listener.simple.retry.*` 这一组配置，测试日志里栈顶的类是这样的

```text
at org.springframework.core.retry.RetryTemplate.execute(RetryTemplate.java:174)
   ~[spring-core-7.0.9.jar:7.0.9]
at org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor.invoke(...)
```

![](https://static.xiongneng.me/rabbitmq-retry-flow-20260922005534.png)

看清楚这个包名，`org.springframework.core.retry`，RetryTemplate 来自 **Framework 7 的 spring-core**，不是 Spring Retry 那个独立的 spring-retry 依赖。AMQP 消费重试的引擎就是它。这个发现直接影响两处。

第一处是依赖，重试不需要显式引 spring-retry，spring-core 自带，我的 pom 里一个重试相关的坐标都没有。第二处是语义，实测确认过，**`max-attempts: 3` 计的是重试次数，加上初始投递，一条消息最多被投递 4 次。**毒丸消息的日志里我数得清清楚楚，四次投递，间隔 500 毫秒、750 毫秒、1125 毫秒，标准的 1.5 倍退避。把 `max-attempts: 3` 理解成「总共投 3 次」，重试的计数就永远差一次，我第一版测试断言就是这么写错的。

为了把这个语义钉死，我做了一轮专门的实验：purge 掉两个队列，发一条毒丸消息，盯着日志数投递。第 1 次投递抛异常，等 500 毫秒；第 2 次还是失败，等 750 毫秒；第 3 次失败，等 1125 毫秒；第 4 次失败之后日志里出现 recoverer 的拒收记录，业务队列深度归零，消息出现在死信队列里。四次投递、三次重试、1.5 倍退避，三个数字互相印证，这一页日志我留了底。

还有一点要清楚，重试发生在监听线程里，重试期间这条线程被占着，别的消息在队列里排队。演示场景无所谓，高吞吐场景就得掂量：重试次数给多了，一条毒丸消息能占住一条线程好几秒。这也是死信兜底要尽快接手的原因，别让毒丸在业务队列里久留。

重试耗尽之后走哪条路，`AbstractRabbitListenerContainerFactoryConfigurer` 的字节码里确认过，默认的 recoverer 是 `RejectAndDontRequeueRecoverer`，抛异常拒收并且不再回队。拓扑那一节里业务队列挂着 `x-dead-letter-exchange`，拒收的消息被 broker 自动转进死信队列。三段剧本就此连起来了：偶发失败重试抢救，永久失败拒收，死信队列兜底等人工。

![](https://static.xiongneng.me/rabbitmq-dead-letter-path-20260922005534.png)

这套链路有个前提我必须敲黑板。**死信转发是 broker 的行为，前提是声明队列的时候挂了死信参数。**只开重试不配 DLX 的工程不少见，重试耗尽的消息默认回队无限循环，一条毒丸消息能把消费者钉死。拓扑里那两个参数，不是可选项。

### 幂等，异步化之后必须还的债

消息从「函数调用」变成「网络投递」，一个物理事实就绕不开了：at-least-once。broker 不丢消息的代价是，ack 在网络上丢了它就再投一次，监听方法会收到两条一模一样的订单事件。这不是 bug，是协议的设计取舍，只能在业务代码里消化。

消化的方式就是幂等。**消费前按事件的唯一标识查一遍台账，处理过的直接跳过。**演示工程里的 `NotificationService` 用 `ConcurrentHashMap` 按 `eventId` 记台账，真实工程里这张表落库，加个唯一索引，幂等就是一条 insert 语句的事。顺序必须是先想幂等再上队列，反过来做的话，凌晨三点的第二条相同短信会把这笔债翻出来，这种电话我不想再接第二次。

我拿演示工程验证过这条路径：同一条事件手动投两次，第二次进来台账里 eventId 已经在了，监听方法直接跳过，统计面板里只有一条消费记录。几十行代码换来这种踏实，值。

## 完整案例，跑起来看

照例把整条链路的实测数据摆出来。服务连的是树莓派上的 RabbitMQ 4.3.6，所有数字来自实际运行，工程里那份验证脚本可以原样复现，我每一轮跑完都拿它的输出对过正文。

这一轮演示我按「先正常、再抖动、后毒丸」的顺序跑，每一步之间都 purge 过队列复位计数，所以三段数字既可以独立看，也可以合起来对总账。

创建订单，201 返回订单号，主链路到此为止，通知在后台异步走

```text
POST /api/orders  {"product":"机械键盘","receiver":"熊大"}
→ 201 {"orderNo":"SO1001","product":"机械键盘","receiver":"熊大","status":"CREATED"}
```

![](https://static.xiongneng.me/rabbitmq-first-publish-stats-20260919181702.png)

此刻查统计面板，一条事件已发布、已消费、只投递了一次，发送方确认是 ACK，broker 的回执到了，消息落在了队列里并被消费掉

```json
{"published": 1, "consumed": 1, "retried": 0, "deadLettered": 0,
 "lastConfirmResult": "ACK"}
```

接着演示重试。给另一个订单注入一次模拟故障，然后支付。故障注入后第一次投递失败，500 毫秒后重试成功，台账里这条事件的 attempts 是 2。统计面板里的 eventId 是每条事件的身份证，发布时生成，消费、重试、死信全程带着它走，对账就认它。下面各段记录里的 eventId 我原样照贴，读者复现的时候数值肯定不一样，格式和字段是一致的。

```json
{"eventId": "b9c7c1b5-f31f-4c81-909c-7c6fef0beed3",
 "orderNo": "SO1002", "type": "ORDER_PAID",
 "status": "CONSUMED", "attempts": 2}
```

用户视角是零感知的，支付接口照常返回，短信晚到了半秒。**这就是重试存在的意义，把基础设施的抖动挡在用户看不见的地方。**

最后是毒丸。我发布一条永远消费失败的事件，看它走完全程

```json
{"eventId": "bb1bd810-d061-46a4-b56f-367abc4c3567",
 "orderNo": "SO1001", "type": "POISON",
 "status": "DEAD", "attempts": 4}
```

![](https://static.xiongneng.me/rabbitmq-poison-deadletter-20260919181717.png)

四次投递全部失败，重试耗尽，`RejectAndDontRequeueRecoverer` 拒收不回队，broker 按死信参数把消息转进 `sb4.order.dead`，死信监听器记录台账，deadLettered 计数从 0 变 1。管理台里死信队列的深度此刻是 0，因为兜底消费者把它接走了，consumers 是 1。整条链路，发布、确认、重试、拒收、死信、兜底，每一环都拿数字当铁证。

死信兜底消费者干的事很朴素：记台账、打 error 日志、留给人工。我没有在这个监听器里做任何自动重试，能进死信的消息说明重试已经救不活了，再自动重试一轮只是把同样的失败重复一遍。人工介入之前消息已经安全落在死信队列里，这个设计把「救不活」和「不知道」分开，前者有记录可查，后者才是事故。

最终的全局统计

```json
{"published": 5, "consumed": 4, "retried": 4, "deadLettered": 1,
 "lastConfirmResult": "ACK"}
```

retried 是 4，毒丸贡献 3 次，PAID 事件贡献 1 次，对得上，我自己核过一遍。错误语义照例是 RFC 9457，404 带 `resourceType` 和 `resourceId`，400 的 `errors` 数组逐字段列原因，跟前面几篇一个规格，就不占篇幅了。

## 这一篇的测试怎么写

**异步链路的测试和同步最大的区别是，断言时机不由测试代码控制。**发完请求立刻断言，消费者可能还没跑到，我最早的几个用例就这么红过，检查半天断言本身一点错没有，错的是时机。做法是一个 `untilAsserted` 轮询助手，给定超时时间，断言失败就等 200 毫秒再试，超时才算失败。

超时给多长有讲究，给短了 CI 机器一忙就假失败，给长了真失败要干等。本篇的六个用例我统一给 5 秒，本地跑平均几百毫秒就过，超时只是兜底。轮询间隔 200 毫秒也是权衡过的，再密了对 broker 是无意义的压力，再疏了单次失败的等待体感明显。

```java
private static void untilAsserted(Duration timeout, Runnable assertion) {
    AssertionError last = null;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
        try {
            assertion.run();
            return;                        // 断言过了，收工
        } catch (AssertionError e) {
            last = e;                      // 记下，再等一轮
        }
        Thread.sleep(200);
    }
    throw last;
}
```

六个用例按 `@Order` 串联，每个用例的 `@BeforeEach` 里先 `RabbitAdmin.purgeQueue` 清空两个队列、内存台账复位，保证计数从零开始可断言。**共享 broker 上跑测试，上一轮的残留消息会把精心设计的计数搅得一塌糊涂。**这个习惯是缓存篇的脏缓存教训带过来的，学费只交一次。

顺便交代一下用 `@Order` 串联的原因：六个用例共享一个 broker 和一套计数，跑的顺序会影响计数基线，串起来才可复现。并行跑用例在这个场景里是给自己挖坑，我试过，两个用例同时 purge 对方刚发布的数据，断言结果完全不可信。

转换器的断言我单独说一下

```java
assertThat(rabbitTemplate.getMessageConverter())
        .isInstanceOf(JacksonJsonMessageConverter.class);
```

一行，钉死「自动挂载机制已生效」。**机制类的断言要钉在机制的产物上**，这比只验证业务结果多一层保险，哪天升级 Boot 之后挂载行为变了，这条断言第一个知道。

## 避坑指南

**坑一，@RabbitListener 收到的是字节，不是对象。** 容器里没有唯一的 `MessageConverter` Bean，或者自定义容器工厂时忘了接转换器，监听方法的参数就反序列化不出来。现象是消息被反复 nack，或者参数变成 `Message` 原始类型。先查容器里有几个转换器 Bean，超过一个时自动配置会放弃挂载，监听失效九成是这个原因。

**坑二，max-attempts 的语义要按重试次数算。** 重试引擎是 Framework 7 的 RetryTemplate，`max-attempts: 3` 实测是最多重试 3 次、总共投递 4 次。按「总共 3 次」去写断言，计数永远差一次，我测试就栽在这里。重试间隔、总投递次数，这两个数建议像本篇一样写进测试断言。

**坑三，RabbitTemplateCustomizer 有两个同名类。** Boot 自动配置的在 `org.springframework.boot.amqp.autoconfigure`；`org.springframework.amqp.rabbit.core` 里还有一个同名类，import 到那个，编译报找不到符号。IDE 弹提示的时候看清包名再回车。

**坑四，只开重试不配死信，毒丸消息无限循环。** 重试耗尽后消息默认回队重投，一条永远消费失败的消息会把消费者线程钉死在原地，队列深度不涨，日志疯狂刷错。业务队列声明时必须挂 `x-dead-letter-exchange`，给失败的消息留一条出路。

**坑五，__TypeId__ 裸奔。** 不传信任包前缀的话，消费端对消息头声明的类型是放行的，公网环境里这是一类真实的反序列化攻击面。构造参数里把信任范围收窄到本工程的包前缀，一行代码的事。

**坑六，消费端不做幂等。** at-least-once 是常态。网络抖动丢一次 ack，监听方法就会看到同一条事件两次。按 `eventId` 建台账加唯一索引，处理前先查，重复的直接跳过。这事上线前做是十分钟，上线后做是事故报告。

**坑七，测试连共享 broker 不清队列。** 上一轮残留的消息会让计数断言全面失守，症状是「本地全绿，CI 全红」或者反过来。每个用例开始前 purge 一次队列，成本几毫秒，换来计数断言的确定性。

## 小结

什么场景该上消息队列，我的判断标准就一条：下游挂了你等不等得起。等不起的（通知、积分、审计这类）异步化，等得起的留在主链路里。定了走 RabbitMQ 这条路，我建议的次序是：发送端把 confirm 和 return 都打开，转换器选 Jackson 3 那个并把信任包收窄到自己的包前缀，消费端重试参数从本篇那份 yaml 起步，队列声明时死信参数一次配齐，幂等设计放在上队列之前想。三道防线各管一段，补齐的成本都很低，事后补的代价都很高。

没解决的事也有两件。一是消费端的并发与顺序：多消费者并行时，同一订单的事件可能乱序到达，本篇场景无所谓，要做状态机类消费就得另外设计，我还没在工程里落地方案，不敢瞎给建议。二是毒丸场景的日志核对目前仍要人眼过一遍，验证脚本的断言还没覆盖到日志层，等我把这块补齐再来交代。

## 参考链接

- [Spring Boot 4.1 Reference - Messaging with RabbitMQ](https://docs.spring.io/spring-boot/4.1/reference/messaging/amqp.html)
- [Spring AMQP Reference](https://docs.spring.io/spring-amqp/reference/)
- [Spring AMQP - 接收消息与监听容器](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages.html)
- [RabbitMQ 官方文档](https://www.rabbitmq.com/docs)
