---
title: SpringBoot4系列07 - Redis数据库
slug: sb4-redis
date: 2026-09-23 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Redis, Lettuce, RedisTemplate ]
draft: false
---

上一篇结尾留了个话头，说从这一篇开始进 `P-2`，第一个要面对的问题是「Redis 的操作什么时候算成功，跟本地事务没有半点关系」。

这一篇就是。

Redis 不太愿意把自己叫数据库，官方给它的定位是数据结构服务器。String、Hash、List、Set、Sorted Set 这些结构在它这里是一等公民，每个命令都是原子操作，进去了就出结果，没有一半提交一半回滚这回事。

这个定位上的差别，写代码的时候处处能感觉到。最典型的例子是验证码。

邮箱注册场景人手一个的功能：五分钟内有效，发完 60 秒内不许再发，错满五次作废。这道题我先拿 MySQL 在脑子里过了一遍：建一张表，写三个字段，再配一个定时任务，每隔几分钟把过期的行扫掉。写到定时任务那一步我就停了，清理任务忘了部署或者扫表间隔配大了，过期的验证码就一直在表里躺着，这种隐患在代码评审里还看不出来。

换 Redis 做这道题，设键的时候带一个过期时间，完事。

到点 Redis 自己删，一行清理代码都不用写。本篇的工程就是这么做的：验证码的「有效期」、冷却的「60 秒」、作废的「错五次」，全部由键的 TTL 表达，业务规则和过期时间是一回事。我后来把这个对比想了很久，结论是 Redis 把「状态是有寿命的」这件事做成了原生能力，关系型数据库里那套要靠表结构和定时任务模拟。差距不在功能，在清理这步活儿归谁。

![](https://static.xiongneng.me/redis-ttl-is-the-business-rule-20260922000138.png)

这一篇的工程在 `springboot4-redis` 目录下，场景就是上面这个验证码，外加一个访问统计，九个测试用例全部连着真实 Redis 跑，服务端是树莓派上的 Redis 8.0.6。每个接口我都在真实运行里手工过了一遍，下面的输出全部是那次运行的原始记录。

照例，先交代 4.1.1 里 Redis 这一块的现状。这一段我翻了本地仓库里 4.1.1 的制品：POM、字节码、配置元数据都看过。结论是，Redis 在 Boot 4 上的改动比 MongoDB 少，但序列化器这一块特别容易带偏人，我把它放在第一个讲。

## 4.1.1 里 Redis 的现状

### 自动配置模块的包名与组成

Boot 4.1.1 里，Redis 的自动配置位于独立模块 `spring-boot-data-redis`，包名是

```text
org.springframework.boot.data.redis.autoconfigure
```

属性类叫 `DataRedisProperties`，连接工厂的组装逻辑、`DataRedisConnectionDetails`、健康检查、可观测这一摊，全在这个包底下。工程代码里包名写错的话，编译期直接报错，藏不住。

starter 的组成，我用 `dependency:list` 跑了一遍，结果是这样

```text
spring-boot-starter-data-redis
├── spring-boot-starter
├── spring-boot-data-redis      ← 自动配置本体（4.1.1 新拆出来的）
│   ├── spring-data-redis:4.1.1
│   └── lettuce-core:7.5.2.RELEASE   ← 默认驱动，直接带进来
└── spring-messaging
```

两个要点。一是默认驱动 Lettuce 是**非可选依赖**，starter 直接带入 7.5.2.RELEASE，想用 Jedis 得自己另加 `redis.clients:jedis`，BOM 管着版本（7.4.1），但不会主动引入。二是 starter 里**没有 Jackson**，后面讲序列化器的时候这一条会变得很重要。

### 属性前缀保持 spring.data.redis

4.1.1 里 Redis 的连接属性就写在 `spring.data.redis` 下面。`DataRedisProperties` 的 `@ConfigurationProperties` 注解上写的是 `spring.data.redis`，配置元数据里 45 条正式属性全部在这个前缀底下。

**4.1.1 里 Redis 的连接属性就写在 spring.data.redis 下面，别改。**

写成 `spring.redis.*` 会命中 error 级废弃标记，应用启动直接失败。这一条在配置元数据里写得清清楚楚，level 是 error。error 级废弃没有告警余地，05 篇讲过，写了它启动就是失败。

![](https://static.xiongneng.me/redis-prefix-not-renamed-20260922000138.png)

所以正确的记忆方式：Redis 的属性前缀是 `spring.data.redis.*`。我自己的记法是把 MongoDB 和 Redis 分开记，看到「Redis 属性改名」这类讲法时先核对版本，写错前缀的代价是启动失败。

### 两套 JSON 序列化器并存

这一节是本篇的重头。

`spring-data-redis:4.1.1` 的 `serializer` 包里，JSON 序列化器有两套并存

| Jackson 2（旧） | Jackson 3（新） |
|---|---|
| `GenericJackson2JsonRedisSerializer` | `GenericJacksonJsonRedisSerializer` |
| `Jackson2JsonRedisSerializer` | `JacksonJsonRedisSerializer` |

名字只差一个字符，用的是两个完全不同的库。带 2 的那套吃 `com.fasterxml.jackson`，不带的那套吃 `tools.jackson`，也就是 Jackson 3。

系列前几篇反复出现的一个事实在这里再次应验：**starter 和自动配置模块都不带 Jackson**。这两个序列化器对应的依赖在 `spring-data-redis` 的 POM 里全部标了 optional，Boot 的逻辑是序列化器开发者来选、Jackson 开发者来带。Web 工程天然没问题，`spring-boot-starter-webmvc` 会把 Jackson 3 带进来（`dependency:list` 实测 3.1.5），所以本篇工程用带 webmvc 的那套组合，classpath 上只有 `tools.jackson`，带 2 的那套序列化器直接是废的，new 出来在运行期才会炸。我第一次扫这个包的时候，把带 2 的类名当成了顺手的选择，编译器一声不吭，这个坑埋得相当深。

![](https://static.xiongneng.me/redis-serializer-two-generations-20260922000138.png)

于是只剩一个选择：用 Jackson 3 的 `GenericJacksonJsonRedisSerializer`。它还牵出一个 API 层面的细节，单独开一节讲。

### 测试注解的包名与测试 starter

`@DataRedisTest` 位于

```text
org.springframework.boot.data.redis.test.autoconfigure
```

同一个包里还有 `@AutoConfigureDataRedis` 和配套的 TypeExcludeFilter。测试 starter 是 `spring-boot-starter-data-redis-test`，它和 MongoDB 的测试 starter 一样**不含 webmvc**，端到端 HTTP 测试要另外补 `spring-boot-starter-webmvc-test`。

## 依赖和配置

pom 的依赖部分，完整版在仓库里，这里说三处有讲究的

```xml
<!-- Redis 主依赖 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Lettuce 连接池的前置条件，见下文 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

第一处讲究，为什么显式加 `commons-pool2`。要配 Lettuce 连接池，也就是 `spring.data.redis.lettuce.pool.*` 这一族属性，前提是 classpath 上有 commons-pool2。而它**不是传递依赖**，`spring-data-redis` 的 POM 里只有它进 dependencyManagement 的影子，版本有人管，包没人带。漏了它，池配置不会报错，只会静默不生效，连接永远不进池。说实话，这种「配了等于没配」的问题排查起来最耗人，表现和原因隔着两层，日志里一个字都不提，所以 pom 里我把它写成显式依赖，注释里写明因果。

第二处讲究，没有加 Jackson。webmvc starter 已经带了，原因上面说过。

第三处，application.yml 的连接配置长这样

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:192.168.1.97}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:root123456}
      timeout: 2s
      connect-timeout: 2s
      lettuce:
        pool:
          enabled: true
          max-active: 8
          max-idle: 8
          min-idle: 2
```

注意前缀，`spring.data.redis`，原因前面讲过，不再重复。

测试环境在 `application-test.yml` 里把 `database` 从 0 切到 1。Redis 默认开 16 个逻辑库，编号 0 到 15，这是它最朴素的隔离手段，键空间物理分开，测试数据随便造，我的测试环境就是这么隔出来的。多说一句，这 16 个库只适合做环境隔离，不适合做业务隔离，一个项目里塞不下几个业务方，而且很多集群模式底下逻辑库不可用，别把它当多租户方案使。

## 核心代码

### RedisTemplate 定制，以及 JDK 序列化为什么不能用

自动配置给了两个现成的模板 bean，`RedisTemplate<Object, Object>` 和 `StringRedisTemplate`。前者看着通用，是个陷阱：key 和 value 都走 **JDK 序列化**。

JDK 序列化写进 Redis 的东西，redis-cli 里看到的是 `\xac\xed\x00\x05` 开头的二进制乱码，存的对象必须实现 `Serializable`，跨语言读不了，升级类结构还有兼容问题。四条占全，没有一条能忍，我排查线上问题的时候，光是把乱码还原成对象这一步就够烦的。

所以教程工程的做法是自定义一个模板，key 用字符串、value 用 JSON

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.xncoding.")
            .build();

    GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.create(
            builder -> builder.enableDefaultTyping(typeValidator));

    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(RedisSerializer.string());
    template.setHashKeySerializer(RedisSerializer.string());
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);
    return template;
}
```

键选字符串序列化器，还有个额外的好处：键名在 redis-cli 里保持人类可读，`captcha:me@example.com` 就长这样，出问题的时候直接就能上手排查，不用先解一层码。

顺带交代驱动选型。

Lettuce 和 Jedis 是 Java 生态唯二的主流 Redis 客户端。Jedis 是同步阻塞的实现，一个连接同时只能跑一条命令，配池子是命根子。Lettuce 从 6.x 起基于 Netty，连接是线程安全的，一个连接可以被所有线程共享，天生支持响应式和集群拓扑自动刷新。Boot 的默认值选了 Lettuce，而且 4.1.1 里把它做成了非可选依赖。我的建议是跟着默认走，除非项目里有非 Jedis 不可的历史包袱。

注意一个细节，`spring-boot-data-redis` 模块的依赖里多了一个 `spring-boot-netty`，这是 4.x 拆出来的独立模块。Netty 本来就是 Lettuce 的传输层，Boot 4 只是把这层关系在模块化的时候摆到了明面上，功能上没有可感知的变化，不用为它做任何事。

### GenericJacksonJsonRedisSerializer，没有无参构造

这一节讲最容易踩的那个坑。

`GenericJacksonJsonRedisSerializer` **没有无参构造**，`new` 一下用不了。对着 4.1.1 的字节码确认过，可用的入口只有吃 `ObjectMapper` 的构造器，外加一组静态工厂，`create(...)` 和 `builder()`。

多态类型信息是一个**必须显式拨动的开关**。看上面那段代码，`enableDefaultTyping(typeValidator)` 这一行就是在拨它，并且 API 强制同时给一个 `PolymorphicTypeValidator`，用来限制反序列化时允许还原哪些类型。

为什么要强制。因为「JSON 里带类型、读的时候按类型实例化」是反序列化攻击的经典入口：攻击者构造一个 `@class` 指向恶意类的 JSON，应用读进来就替攻击者完成了实例化。Jackson 3 的方案是把默认值改成安全，把不安全变成显式选项，同一个类里留着 `enableUnsafeDefaultTyping()` 方法，名字都替调用方写好了。

示例工程里 validator 只放行 `com.xncoding.` 开头的类型，这是工程自己的包。别的项目换成对应包名就行，原则只有一条，**白名单放行自己的类型，其余全部拒绝**。

### record 进不了多态通道

还有一个容易撞上的暗坑，我测试就栽在这里。

Java 21 之后，存的对象用 record 看起来很自然，但这条路走不通，这个决定连同原因写进了 `CaptchaRecord` 的注释里。

原因是默认类型信息只对**非 final** 类型生效。record 是 final 类，序列化的时候 JSON 里不带 `@class`，读回来 Jackson 不知道该实例化成谁，只能退化成 `LinkedHashMap`。代码拿着 Map 当 `CaptchaRecord` 用，编译期一点毛病没有，运行期在 `instanceof` 那里判空走异常分支。挺有意思的一点是这个失败特别安静：不报错、不打日志，就是数据悄悄变了形。

切片测试里验证过普通类的往返是通的，类型、字段值、时间戳全部原样还原。record 要走通得额外注册类型解析，性价比不高，不值得为一个语法的舒适度引入特殊配置，所以我没做。

**存进 Redis 的领域对象，用普通类，别用 record。**

### 验证码，TTL 就是业务规则

发送验证码的完整逻辑

```java
public CaptchaSendResult send(String email) {
    Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(KEY_LIMIT + email, "1", RESEND_COOLDOWN);
    if (!Boolean.TRUE.equals(acquired)) {
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "发送过于频繁，请 60 秒后再试");
    }

    String code = "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
    CaptchaRecord record = new CaptchaRecord(code, Instant.now());
    redisTemplate.opsForValue().set(KEY_CAPTCHA + email, record, CAPTCHA_TTL);

    return new CaptchaSendResult(email, CAPTCHA_TTL.toSeconds(), RESEND_COOLDOWN.toSeconds());
}
```

两个键，`captcha:{email}` 存验证码本体，TTL 五分钟，`captcha:limit:{email}` 是冷却标记，TTL 六十秒。没有定时任务，没有状态字段，过期这个概念 Redis 原生承担。

![](https://static.xiongneng.me/redis-key-layout-20260922000138.png)

### setIfAbsent，一个命令顶一层锁

第一行值得单独讲。

`setIfAbsent` 对应 Redis 的 `SET key value NX EX`，意思是这个键不存在才写入，写入成功带过期时间，整条命令在 Redis 侧一次执行完。

它解决的问题很具体：接口被并发打的时候怎么办。两个请求同时到了服务端，都执行「检查 60 秒内发过没有，没有就发」，如果检查和写入是两步，两个请求都能通过检查，验证码发两遍。把检查和写进冷却标记合成一条原子的 `setIfAbsent`，两个请求只有一个能拿到 true，另一个拿到 false，走 429。

有人在这儿先 `synchronized` 再查再写。单机下能跑，应用起了两个实例就穿。反过来想，锁的目的是让「检查加写入」成为不可分割的动作，而 Redis 本来就是单线程执行命令的，这条命令天生不可分割，锁白加了。

**分布式语义的互斥标记，用 SET NX，不用锁。**

顺带一提，这个模式就是很多分布式锁实现的底座，Redisson 的那些花活都在这条命令上长出来的。理解了 setIfAbsent，再看分布式锁的源码会亲切很多。

![](https://static.xiongneng.me/redis-setnx-race-20260922000138.png)

### 写回时丢 TTL 的经典事故

校验的逻辑是读出来、比一比、错了累加次数、对了删键。中间有个步骤要放大看

```java
record.setAttempts(record.getAttempts() + 1);
if (record.getAttempts() >= MAX_ATTEMPTS) {
    redisTemplate.delete(key);
    throw new BusinessException(HttpStatus.FORBIDDEN, "失败次数过多，验证码已作废，请重新获取");
}

// 写回时把剩余 TTL 一起带上
Long remaining = redisTemplate.getExpire(key);
redisTemplate.opsForValue().set(key, record, remainingTtl(remaining));
```

问题来了，为什么写回要带 TTL。

因为 RedisTemplate 的 `set(key, value)` 不带过期参数的时候，是**覆盖整个键的元数据**，原来键上的过期时间会被清掉，一个本来五分钟就消失的键，被写成了永久键。

这大概是 Redis 应用最经典的线上事故之一。验证码过了有效期还在 Redis 里躺着，攻击面、内存、合规，每一头都出问题。而且它没有报错，测试里只断言「校验返回 false」的话照样全绿，去看键的 TTL 才能发现。我自己的测试就漏过这一层，功能断言全过，回头一查 TTL，铁证摆在那里，键早就成永久键了。

本篇的测试里专门有一步对账：错四次之后去读键的剩余 TTL，断言它还大于零且不大于发送时的时间。**凡是要把对象写回去的地方，先问一句，这个键上的 TTL 还在吗。**

顺着 TTL 再讲一个 API 层的细节，`getExpire` 的返回值。

这个方法返回的是键的剩余秒数，但它有三种可能的取值。正数，正常，还剩这么多秒。负一，键存在但没有过期时间，永久键。负二，键不存在。

第三种最容易被漏。先读键再读 TTL，两步之间键正好过期的话，`getExpire` 给负二，代码没防着就会拿去拼 Duration，结果是异常或者一个荒唐的剩余时间。`remainingTtl` 里那个 `remainingSeconds <= 0` 的兜底分支，防的就是这个窗口。**TTL 的读和键的读永远是两个命令，中间键可能消失，读到的每个负数都要有去处。**

另外，这段读改写不保证原子。两个请求并发校验同一个邮箱，attempts 可能少算一次。示例工程留着这个不精确，因为验证码场景多错一次的代价是零。什么时候可以不较真、什么时候必须较真，开发者自己心里要有数：需要严格原子计数的场景，看下面这节。

### INCR，为什么不用读加一写

访问统计的计数器

```java
public VisitStatsResponse recordVisit(String email) {
    Long total = redisTemplate.opsForValue().increment(KEY_TOTAL);
    ...
}
```

`increment` 对应 `INCR`，原子自增，两个并发请求分别拿到 1001 和 1002，不重不漏。

要是用「读出来，加一，写回去」，并发下两个请求同时读到 1000，都写出 1001，少计一次。06 篇看到的「数据对不上但又说不出哪错了」，放在这个计数器上就是丢计数，而且永远不知道丢在哪次。这类问题我最怕的不是丢，是丢了你还不知道。

当天去重访客用集合

```java
redisTemplate.opsForSet().add(KEY_VISITORS + today, email);
Long unique = redisTemplate.opsForSet().size(KEY_VISITORS + today);
```

`SADD` 天然去重，同一个人一天来八百次，集合里只有他一个，`SCARD` 拿集合大小就是去重访客数。键里带日期，隔天自然切新集合。

### 一次请求到底发了几条命令，日志对账

开发配置里把 `org.springframework.data.redis.core` 开到了 DEBUG，它会输出每次连接的借还。真实运行的时候，发送验证码那一次请求，日志是这样的

```text
DEBUG o.s.d.redis.core.RedisConnectionUtils : Fetching Redis Connection from RedisConnectionFactory
DEBUG o.s.d.redis.core.RedisConnectionUtils : Closing Redis Connection
DEBUG o.s.d.redis.core.RedisConnectionUtils : Fetching Redis Connection from RedisConnectionFactory
DEBUG o.s.d.redis.core.RedisConnectionUtils : Closing Redis Connection
```

一对 Fetching 和 Closing 就是一次连接借还。`send()` 方法里两笔命令，`setIfAbsent` 一笔，带 TTL 的 `set` 一笔，日志里正好两对。

这个数日志的习惯是 06 篇传下来的。事务那篇靠数「提交次数加回滚次数」和连接借还数的差值抓出了 NESTED，这一篇我用同样笨的办法确认每条路径下发的命令数。状态查询那个接口，`get` 一笔加 `getExpire` 一笔，也是两对。校验失败的路径是三对，`get`、`getExpire`、写回的 `set`。

这套数法看着原始，但有一个无可替代的好处，**不依赖对框架行为的理解，只依赖事实本身**。以为只会发一条命令的接口，日志里数出来三条，这种事读十遍文档不如数一遍日志。

### StringRedisTemplate 和 RedisTemplate 的分工

统计这个服务里注入的是 `StringRedisTemplate`，验证码那个服务用的是自定义的 `RedisTemplate<String, Object>`。

这有明确的原因。`StringRedisTemplate` 是自动配置自带的 bean，key 和 value 都是字符串序列化器，直接对应 Redis 的文本世界。计数器、集合这类场景，值本身就是简单字符串，用它最顺手。验证码的 value 是一个对象，需要 JSON 序列化，才轮到自定义模板出场。

一个经验法则，**value 是字符串就用 StringRedisTemplate，value 是对象才上 RedisTemplate。** 两个都用上的工程才是正常形态，别为了统一注入硬把字符串塞进 Object 模板。

### 异常层比上一篇干净

对比 06 篇那边的异常处理，这一篇的 `GlobalExceptionHandler` 少了两个处理器，乐观锁和唯一索引冲突，全删了。

原因不难想。Redis 没有版本号机制，没有唯一索引，那两类冲突在数据结构服务器这个语境里不存在。写操作要么成功要么连接失败，业务层面的冲突全靠键的语义自己表达，本篇工程里就是冷却期的 429 和错满五次的 403。

上一篇那些「异常穿过几层框架之后类型都变了」的问题，到这里一个都没有。每种中间件有它自己的脾气，接入之前想清楚它给什么、不给什么，异常层自然就知道该写什么。

## 完整案例，跑起来看

服务起来之后，整条链路我手工过了一遍，下面的输出都是真实运行抓的。

发送

```text
POST /api/captchas  {"email":"shot-1789802034@example.com"}
201 {"email":"shot-1789802034@example.com","expiresInSecond":300,"resendAfterSecond":60}
```

![](https://static.xiongneng.me/redis-captcha-created-201-20260919151356.png)

六十秒内重发，撞上冷却标记

```text
429 Too Many Requests
```

![](https://static.xiongneng.me/redis-resend-429-20260919151358.png)

查状态，剩余秒数和剩余尝试次数都在

```text
GET /api/captchas/shot-1789802034@example.com
200 {"email":"shot-1789802034@example.com","remainingSecond":296,
     "remainingAttempts":5,"createdAt":"2026-09-19T07:13:55.329326500Z"}
```

![](https://static.xiongneng.me/redis-captcha-status-200-20260919151400.png)

空邮箱，参数校验，RFC 9457 格式的错误响应

```text
POST /api/captchas  {"email":""}
400 {"detail":"请求体字段校验未通过，逐字段原因见 errors","instance":"/api/captchas",
     "status":400,"title":"请求体校验失败",
     "errors":[{"message":"邮箱不能为空","field":"email"}]}
```

统计接口，连打两次，总数精确加二，同一个邮箱重复上报，去重访客数不动

```text
GET /api/stats/visits?email=...    {"totalVisits":1,"date":"2026-09-19","todayUniqueVisitors":1}
GET /api/stats/visits?email=...    {"totalVisits":2,"date":"2026-09-19","todayUniqueVisitors":1}
```

校验的错误路径，拿一个错码去试，返回的是 200 加 `verified:false`，错误校验按业务结果返回，不走异常

```text
POST /api/captchas/verify  {"email":"...","code":"000000"}
200 {"email":"...","verified":false}
```

错满五次之后，键已经被服务端删了，这时候再校验或者查状态，全是 404

```text
GET /api/captchas/...   404 application/problem+json
```

手动作废，DELETE 删键，204

```text
DELETE /api/captchas/...   204
```

删除一个已经不存在的验证码，返回 404。过期、校验成功后的自动删除、手动作废，三条路最后都汇到同一个语义上，**键没了就是不存在，对上层一个样**。这就是把状态放进 Redis 的世界观：不用维护一个「已作废」状态机，键的存在性本身就是状态。

最后是本篇最想展示的部分。打开 redis-cli，直接读验证码那个键

```text
GET captcha:me@example.com
{"@class":"com.xncoding.redis.domain.CaptchaRecord","code":"860476","attempts":0,
 "createdAt":"2026-09-19T07:13:55.329326500Z"}
```

![](https://static.xiongneng.me/redis-browser-status-json-20260919151401.png)

`@class` 就在第一个字段，前面讲的默认类型信息长这样。值是纯 JSON 文本，redis-cli 里一眼看懂，Python 脚本一读就解析，这就是换掉 JDK 序列化的意义。排查线上问题的时候，能直接看懂数据的人，和要拉起一套反序列化环境才能看数据的人，效率不是一个量级。

## 这一篇的测试怎么写

九个用例，分两摊。

切片测试用 `@DataRedisTest`，注意包名，还要**显式 `@Import(RedisConfig.class)`**。切片只装 Redis 那一摊自动配置，自定义的配置类不会被扫进来，不 Import 的话注入的就是自动配置那个 JDK 序列化的模板，类型直接对不上。这个约束反而是个提醒：value 序列化器换没换，切片测试第一个知道。

四个用例的骨架长这样

```java
@DataRedisTest
@ActiveProfiles("test")
@Import(RedisConfig.class)
class RedisSerializationSliceTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void recordRoundTrip() {
        String key = "test:roundtrip:" + System.nanoTime();
        try {
            redisTemplate.opsForValue().set(key, new CaptchaRecord("483920", ...),
                    Duration.ofMinutes(5));
            Object value = redisTemplate.opsForValue().get(key);
            // 类型还原成功，拿到的是 CaptchaRecord 而不是 Map
            assertThat(value).isInstanceOf(CaptchaRecord.class);
        } finally {
            redisTemplate.delete(key);
        }
    }
}
```

每条用例的键都带 nanoTime 后缀，`finally` 里删键，用例之间互不踩。

切片里测的四件事：对象往返后类型还原成 `CaptchaRecord` 而不是 Map、原始值是带 `@class` 的 JSON 文本、TTL 生效、INCR 原子性。都是这一篇的关键机制，不是接口行为。

端到端用 `@SpringBootTest` 加 `@AutoConfigureMockMvc`，连真实 Redis 走完整 HTTP 栈。每个用例对关键事件单独计数，比如统计接口那两次访问，断言写的是第二次的 total 等于第一次加一，而不是「两次都返回 200 就算过」。06 篇那个教训记到现在，**只看状态码的测试，会放过所有对不上数的问题**。

测试数据用带运行后缀的独立邮箱，收尾统一清理，全局计数器用 `DECR` 回退增量，演示数据不被测试抬高。两个测试类开头都做了可用性探测，Redis 连不上就整体 skip，不做一片红。

## 避坑指南

这一篇的坑，大半是「静默」两个字的变体，我基本都亲手碰过。

**坑一，写成 spring.redis.* 前缀，error 级废弃，启动即失败。** 正确前缀 `spring.data.redis.*`，4.1.1 沿用没变。别把 MongoDB 那篇的前缀改名结论平移到 Redis 上，我就差点平移过去。

**坑二，commons-pool2 不是传递依赖，漏了它池配置静默失效。** 表现为连接池永远不建，不报任何错。显式加依赖，别指望 starter 帮忙带。

**坑三，GenericJacksonJsonRedisSerializer 没有无参构造，多态类型信息是显式开关。** 用 `create(...)` 或 `builder()`，配 `BasicPolymorphicTypeValidator` 白名单放行自己的包。看到 `enableUnsafeDefaultTyping` 绕道走。

**坑四，存 Redis 的对象用 record，读回来会变成 LinkedHashMap。** 默认类型信息只对非 final 类型生效。存进 Redis 的领域对象用普通类。

**坑五，不带 TTL 的 set 会清掉过期时间，临时键变永久键。** 读改写之后写回时，把剩余 TTL 一起带上。测试要专门断言 TTL 还在。

**坑六，自动配置的 RedisTemplate 走 JDK 序列化，别直接用。** 要么自定义 JSON 序列化的模板，要么 value 本来就是字符串就直接用 StringRedisTemplate。

## 小结

这一篇的接口行为表

| 操作 | 行为 | 本篇的实现 |
|---|---|---|
| 有限寿命的状态 | 写入时带 TTL，到期自动消失 | `set(key, value, duration)` |
| 一次性的标记 | 不存在才写入，原子 | `setIfAbsent(key, value, duration)` |
| 并发安全的计数 | 服务端自增 | `INCR` |
| 去重计数 | 集合 | `SADD` + `SCARD` |
| 主动作废 | 删键 | `DELETE` |

怎么选型，我的建议按 value 的形态走：值是字符串或纯数字，`StringRedisTemplate` 加上 `INCR`、`SADD` 这类服务端命令就够了，别引入对象序列化；值是业务对象，才自定义模板上 JSON 序列化器，并且把白名单 validator 一起配上。键的设计比配置重要，键名怎么组织、TTL 给多久、哪些操作要靠原子命令兜底，这三个问题想清楚了，Redis 就用对了。

没解决的事也说清楚。读改写校验那段的非原子窗口我留着没修，验证码场景的代价是零，真要严格计数得换 Lua 脚本或者 WATCH 事务，那是另一个主题的篇幅。切片测试只覆盖了序列化与 TTL，连接池行为、超时重试这些运行期特性没有专门用例，等后面讲到生产配置的时候我再补测。

工程在 `springboot4-redis` 目录下，自带 README，四个接口手工测试的 curl 命令都在里面。建议跑一遍，然后在 redis-cli 里开着 `MONITOR` 看每次点接口时 Redis 收到了什么命令，看一遍比读三遍代码有用。

署名 `Xiong Neng`，许可 MIT。

## 参考链接

- [Spring Data Redis 官方参考文档](https://docs.spring.io/spring-data/redis/reference/)
- [Spring Boot 4.1 Reference - Redis](https://docs.spring.io/spring-boot/4.1/reference/data/nosql.html#data.redis)
- [Redis 官方命令手册](https://redis.io/commands/)
- [Lettuce 官方仓库](https://github.com/redis/lettuce)
