---
title: SpringBoot4系列08 - 使用缓存
slug: sb4-cache
date: 2026-09-30 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Cache, Redis, Cacheable ]
draft: false
---

上一篇讲 Redis 收尾的时候留了句尾巴：RedisTemplate 那套是「自己动手」的用法，键怎么起、TTL 给多少、什么时候删，全是我自己说了算。自己动手自由，可这些手工操作散落在业务代码各处，时间一长就没人说得清全貌了。这一篇讲另一条路，Spring 从 3.1 就备好的声明式缓存：方法上贴个注解，命中不进方法体，未命中执行完自动写缓存，删除也是注解一句话的事。

第一次看到 `@Cacheable` 的时候，我心里嘀咕的是「这不就是给方法套了个 HashMap 吗，能有多大事」。后来真在一个订单系统里用起来才发现，这件工具值钱的地方不在快，在治理：TTL 策略收在一个配置类里，序列化方式收在一个地方，清缓存的动作跟着方法签名走，改缓存策略不用翻遍所有业务代码。说实话，用顺了之后我很难再回到手工维护键的写法。

这个系列走到这里，规律一直是同一个：框架替开发者做掉的部分越多，越需要知道它做了什么、没做什么。声明式缓存在这条路上走得特别远，远到少一行注解就**一声不吭地什么都不做**，连个警告都不给。我在这上面栽过，后面每一节都对应一个实际踩过或者差点踩进去的坑。

![](https://static.xiongneng.me/cache-three-annotations-20260922002844.png)

这一篇的场景是订单。我模拟了一个 300 毫秒的慢查询，查询、支付、取消三条业务线，分别踩在 `@Cacheable`、`@CachePut`、`@CacheEvict` 三个注解上，缓存落在上一篇搭好的 Redis 8.0.6 上。工程在 `springboot4-cache` 目录，十个测试用例全连真实 Redis 跑，正文里的数字都是同一份验证脚本一口气跑出来的。

照例，先过一遍 Boot 4 上缓存这一块的现状。

## 4.1.1 里缓存的几个变化

我拿着本地仓库里 4.1.1 的制品把缓存这一块翻了一遍，POM、字节码、配置元数据都看了。结论是，缓存这块的改动比 Redis 还要小。有一个地方藏得深，放在后面单独讲。

### starter 极简，不带任何实现

`spring-boot-starter-cache` 的 POM 我拆开看了，就两个依赖

```text
spring-boot-starter-cache
├── spring-boot-starter
└── spring-boot-cache            ← 自动配置本体
    ├── spring-boot
    └── spring-context-support   ← CaffeineCacheManager 这些实现类的家
```

上一篇 Redis starter 那一长串依赖树看惯了，再看这个短得让人不适应。它不带任何一种具体的缓存实现，没有 Caffeine、没有 EhCache、也没有 Redis 客户端。它带的是 Spring 的缓存抽象，`CacheManager` 接口、注解解析、AOP 拦截这一层，全部在 spring-context 里，Boot 给这层抽象装的是「自动配置」外壳。

![](https://static.xiongneng.me/cache-starter-anatomy-20260922002844.png)

所以想用 Redis 当缓存，工程里必须同时引入 `spring-boot-starter-data-redis`。缓存自动配置排在 `DataRedisAutoConfiguration` **之后**装配，这个顺序我核对过，写在 `@AutoConfiguration` 的 afterName 里。它直接拿容器里现成的 `RedisConnectionFactory` 用，连接配置由 `spring.data.redis.*` 那一组说了算，`spring.cache.*` 管不着连接这一层。

这个分工排障时要分清楚：连不上 Redis 查数据源配置，值对不对查缓存配置，两摊事。

### 自动配置在独立的 cache 模块

规律跟 MongoDB、Redis 两篇一致：缓存自动配置在独立模块 `spring-boot-cache` 里，包名是

```text
org.springframework.boot.cache.autoconfigure
```

actuator 的 `CachesEndpoint`、metrics 那一套也在这个模块里。自动配置注册文件里有三条记录，`CacheAutoConfiguration`、`CachesEndpointAutoConfiguration`、`CacheMetricsAutoConfiguration`，我在注册文件里一条条数过。

有一个例外，是我从 `CacheProperties` 的字节码里核对出来的。它的 `getType()` 方法返回类型是 `CacheType` 枚举，这个枚举的包名在

```text
org.springframework.boot.autoconfigure.cache.CacheType
```

枚举本体在 `spring-boot-autoconfigure` 模块里，cache 模块跨包引用它。写代码不受影响（枚举值 `REDIS`、`CAFFEINE` 照用），但你要按包名规律找 cache 相关的类时，这个例外得知道，我第一次就照着规律找扑了个空。

十种缓存类型我也都数过了，`GENERIC / JCACHE / HAZELCAST / COUCHBASE / INFINISPAN / REDIS / CACHE2K / CAFFEINE / SIMPLE / NONE`，自动配置按这个顺序探测，谁在 classpath 上就选谁。工程里同时有 Redis 连接工厂时，轮到 REDIS 就定下来了。显式指定是 `spring.cache.type: redis` 一行的事。

### 属性前缀是 spring.cache

`@ConfigurationProperties` 的注解值我从字节码里读出来了，是 `spring.cache`，正式属性 12 条。

```yaml
spring:
  cache:
    type: redis              # 不配就自动探测
    redis:
      time-to-live: 30m      # 全局默认 TTL
      cache-null-values: false
      use-key-prefix: true
      key-prefix: "sb4:cache:"
```

`spring.cache.redis.*` 这一组管的是「用 Redis 当缓存」时的全局默认：TTL、键前缀、null 值、统计开关。注意「全局默认」这四个字，后面有个坑就坑在这：一旦按缓存名单独定制，这些全局值对对应缓存就整个失效。

探测顺序我顺带说透。`spring.cache.type` 不配的时候，自动配置按 `CacheConfigurations` 里登记的顺序逐个问「这个类型的条件满足吗」，GENERIC 在最前，SIMPLE 在倒数第二，NONE 收尾。这个顺序挺有意思：REDIS 排在 CAFFEINE 前面，工程里为了别的原因引了 Caffeine 的 jar，探测到 REDIS 就停，Caffeine 进了 classpath 也轮不到它；反过来引的是 Hazelcast，就会被抢注，Hazelcast 排在 REDIS 前面。所以我的建议很直接，**显式写一行 spring.cache.type**，把自动探测关掉，配置文件里花一个词，省掉以后所有「为什么缓存忽然换实现」的排查时间。

另外提一句 actuator 的 `CachesEndpoint`，随 cache 模块在同一个包里。它能按 CacheManager 列出所有缓存名和条目，排障好用。工程里没引 actuator starter 我就不展开配置了，需要的时候引 `spring-boot-starter-actuator` 就有。

### 缓存有专属测试 starter

这条 starter 是我翻 4.1.1 制品翻出来的。`spring-boot-starter-cache-test`，4.1.1 的 BOM 里明明白白登记着，组成是 starter-cache 加 `spring-boot-starter-test` 加 `spring-boot-cache-test`。测试注解 `@AutoConfigureCache` 在

```text
org.springframework.boot.cache.test.autoconfigure
```

它有个 `cacheProvider` 属性，设成 `CacheType.NONE` 就把测试期间的缓存整个关掉，强制走真实现。跑切片测试的时候有用。

## 依赖和配置

pom 全家福，比 Redis 篇多一个 cache starter

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<!-- 缓存抽象与自动配置，不带任何具体实现 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<!-- Redis 连接层：缓存管理器的实现基础 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- 缓存专属测试 starter -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

yml 的完整缓存段在前面讲属性前缀的时候已经给过了，连接段跟上一篇完全一样，不再重复。

![](https://static.xiongneng.me/cache-module-layout-20260922002844.png)

## 核心代码

这一节往后是主菜，每一节对应一个我实际踩过或者差点踩进去的坑。

### @EnableCaching 不加，一切静默失效

先讲最狠的一个。

`CacheConfig` 类上有一个注解是生命线

```java
@Configuration
@EnableCaching
public class CacheConfig {
```

为什么必须自己加？`CacheAutoConfiguration` 的类级注解挂着 `@ConditionalOnBean(CacheAspectSupport.class)`。`CacheAspectSupport` 是注解驱动缓存 AOP 的核心组件，由 `@EnableCaching` 引入的配置注册。不加 `@EnableCaching`，容器里就没有这个 bean，`CacheAutoConfiguration` 整个跳过，`CacheManager` 都不会被装配。

最要命的是失败的形态：不报错。

我测试就栽在这里。方法上贴着 `@Cacheable`，容器安安静静起来，请求来了，方法体老老实实每次执行，查库一次没少。没有任何日志提示「注解没生效」，因为从框架的视角看什么都没发生，没有代理生成，没有拦截发生，一切符合预期。我盯着响应时间排查了半天，先怀疑键写错了，又怀疑路径没对上，最后才发现配置类上少了注解，九成是这个原因。多数据源那篇讲过事务管理器绑错名的毫无提示，这次的静默程度更甚，连间接症状都没有，只有 dbHits 计数器在稳步上涨。

习惯做法是：工程里出现第一个 `@Cacheable`，先写一个「查两次看计数」的测试。这个测试就在工程里，`firstGetHitsDbSecondGetHitsCache`，它守护缓存功能，也守护 `@EnableCaching` 这行注解本身。

### 两个 RedisCacheConfiguration，同名不同包

这一节来自我写工程代码时真实撞上的问题。

配置缓存管理器的时候需要用到 `RedisCacheConfiguration` 这个类，IDE 补全列表里它出现了两次

```text
org.springframework.boot.cache.autoconfigure.RedisCacheConfiguration
org.springframework.data.redis.cache.RedisCacheConfiguration
```

前一个是 Boot 的自动配置类，业务代码完全用不到。后一个是 spring-data-redis 提供的 DSL，配 TTL、配序列化器全靠它。名字一模一样，包挨得很近，补全的时候一不留神选了上面那个，编译都过不了，报错信息也不会提示你选错了类。

![](https://static.xiongneng.me/cache-two-classes-same-name-20260922002844.png)

本工程所有用到这个类的地方，import 固定为

```java
import org.springframework.data.redis.cache.RedisCacheConfiguration;
```

把这一行背下来，配置序列化器和 TTL 的地方都写它。这类坑单人开发也就烦一阵，团队开发里能把一个下午耗掉。

### 默认 JDK 序列化，换 JSON 的完整步骤

现在讲制品里翻出来的那个藏得深的地方。

Boot 自动配置的 Redis 缓存管理器，值序列化器默认是什么？我把 `RedisCacheConfiguration` 自动配置类的字节码反编译看了，`createConfiguration` 方法里明明白白一条指令

```text
JdkSerializationRedisSerializer."<init>":(Ljava/lang/ClassLoader;)V
```

跟上一篇 RedisTemplate 的默认序列化一脉相承，**JDK 序列化**。缓存的对象必须 `implements Serializable`，写进 Redis 的是 `0xAC 0xED` 开头的二进制，redis-cli 打开是一坨乱码，别的语言读不了，对象改一个字段反序列化就炸。

解决方案分两步，都在 `CacheConfig` 里。

第一步，造一个带白名单的 JSON 序列化器，上一篇的同款写法

```java
PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("com.xncoding.")
        .allowIfSubType("java.math.")
        .allowIfSubType("java.time.")
        .build();
GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.create(
        builder -> builder.enableDefaultTyping(typeValidator));
```

第二步，把这个序列化器按缓存名挂上去

```java
@Bean
public RedisCacheManagerBuilderCustomizer perCacheCustomizer() {
    return builder -> builder
            .withCacheConfiguration(OrderService.CACHE_ORDERS,
                    RedisCacheConfiguration.defaultCacheConfig()
                            .prefixCacheNameWith(KEY_PREFIX)
                            .entryTtl(Duration.ofMinutes(10))
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(jsonSerializer)))
            .withCacheConfiguration(OrderService.CACHE_ORDER_FLASH,
                    RedisCacheConfiguration.defaultCacheConfig()
                            .prefixCacheNameWith(KEY_PREFIX)
                            .entryTtl(Duration.ofSeconds(2))
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(jsonSerializer)));
}
```

这里有三个细节，每一个都够一条避坑。

第一个细节是白名单，我在这上面闹过笑话。只写 `allowIfSubType("com.xncoding.")`，跟上一篇一样，测试直接炸了。错误信息是 `Could not resolve type id 'java.math.BigDecimal'`。原因：订单金额是 `BigDecimal`，这个类不是 final 的，Jackson 的 default typing 会给它写入类型 id，读回来的时候这个类型 id 也要过白名单，被 `com.xncoding.` 拦下，反序列化直接失败。现象是**第一笔缓存写进去没问题，第二笔读出来必炸**，缓存从「提速工具」变成「定时炸弹」。修法就是补上 `java.math.` 和 `java.time.`，领域对象里用到的所有非 final 的 JDK 类型都得放行。

第二个细节是 key-prefix，这个结果跟我的直觉相反，单独说一下。yml 里配了 `spring.cache.redis.key-prefix: "sb4:cache:"`，我以为全局前缀会自动带到每个缓存上，但上面的代码里每个缓存名都还得再 `prefixCacheNameWith(KEY_PREFIX)` 一次。原因：`withCacheConfiguration` 传入的是一份全新的 `defaultCacheConfig()`，它把全局默认**整体替换**了，yml 里的前缀、TTL 都带不过来。这个行为是我跑完测试直读 Redis 才确认的，键名裸成了 `orders::SO...`，前缀没了。要么按上面的写法在 per-cache 配置里补前缀，要么接受裸键名，两选一，别悬着。

第三个细节，`prefixCacheNameWith` 里的 KEY_PREFIX 抽成了 `CacheConfig` 的 public 常量，测试断言直接引用同一个常量，键名的前后端约定就不会写岔。

### 缓存对象用普通类，这两个理由都成立

上一篇讲过 record 进不了多态通道，这一篇的 `Order` 继续用普通类，理由从一条变成两条。一是 JSON 反序列化需要无参构造和可变字段，record 两条都不满足，读回退化成 Map；二是就算不动序列化器、用默认配置，JDK 序列化对 record 的支持也是一道额外的坑。技术选型上省的那点键盘敲击，换不来这两条里的任何一条。

```java
public class Order {
    private String orderNo;
    private String product;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
    private Instant payTime;
    // 无参构造 + getter/setter，Jackson 的口粮
}
```

### 订单服务，三个注解各就各位

业务代码非常薄，薄到不像一个订单服务。数据库是一个内存 `ConcurrentHashMap`，慢查询是 `Thread.sleep(300)`，但注解的编排是真编排。

```java
@Cacheable(cacheNames = CACHE_ORDERS, key = "#orderNo", sync = true)
public Order getOrder(String orderNo) {
    dbHits.incrementAndGet();
    simulateSlowQuery();
    Order order = db.get(orderNo);
    if (order == null) {
        throw new ResourceNotFoundException("订单", orderNo, "订单不存在: " + orderNo);
    }
    return order;
}
```

`@Cacheable` 的语义是「先查后执行」。方法调用先被 AOP 拦截，拿 SpEL 表达式算出 key，去缓存里找，找到就直接返回，方法体一行都不执行。未命中才进方法体，返回值自动写入缓存。`sync = true` 管的是同一 key 并发未命中的时刻只放一个请求进去，其余线程原地等结果，这是防缓存击穿成本最低的写法，一个属性的事。

![](https://static.xiongneng.me/cache-order-lifecycle-20260922002844.png)

支付是 `@CachePut`

```java
@CachePut(cacheNames = CACHE_ORDERS, key = "#orderNo")
public Order pay(String orderNo) {
    Order order = mustGet(orderNo);
    if (!Order.STATUS_CREATED.equals(order.getStatus())) {
        throw new BusinessException(HttpStatus.CONFLICT,
                "只有待支付订单可以取消，当前状态: " + order.getStatus());
    }
    order.setStatus(Order.STATUS_PAID);
    order.setPayTime(Instant.now());
    simulateSlowQuery();
    return order;
}
```

`@CachePut` 跟 `@Cacheable` 的区别就一句话：**方法体一定执行**，返回值无条件写进缓存。语义是「写完库之后把最新值同步给缓存」，直接替换，不等下次重查。下一个 GET 进来读到的是 PAID，库都没碰。

这带来一个容易忽略的约束，`@CachePut` 方法必须返回更新后的完整对象。返回 void 或者返回 null，写进缓存的就是 null，下次查询「命中」一个 null。空指针从数据库层挪到了缓存层，还带着 TTL。

取消是 `@CacheEvict`

```java
@CacheEvict(cacheNames = CACHE_ORDERS, key = "#orderNo")
public Order cancel(String orderNo) { ... }
```

方法执行完，这个 key 从缓存里删掉。还有一个全清的变体

```java
@CacheEvict(cacheNames = CACHE_ORDERS, allEntries = true)
public void evictAllOrders() { }
```

`allEntries = true` 清空整个缓存名下的所有条目。什么时候用它？商品价格批量调整、缓存键规则变更这种「与其逐个找不如整块倒掉」的时刻。注意它对大缓存的冲击，全清的那一瞬间所有请求都会未命中，集体涌向后端，这本身就是一个缓存雪崩的触发器，量大的系统要配停机窗口或分批清理。

三个注解的分工到这里齐了。`@Cacheable` 读、`@CachePut` 写、`@CacheEvict` 删，方法体的业务逻辑从头到尾没有出现过一行缓存操作代码，这就是声明式这两个字的全部含义。

### 自调用，注解第二次静默失效

跟事务篇同源的坑，缓存这边原样又来一遍，而且更隐蔽，我单独拿出来讲。

声明式缓存的实现是 AOP 代理。容器里注入的 `OrderService` 是个代理对象，`getOrder` 的调用先经过代理，代理才有机会查缓存。但在 `OrderService` 自己的方法里调自己

```java
public Order getWithDetail(String orderNo) {
    // this.getOrder(...) 走的是原始对象，不经过代理
    Order order = this.getOrder(orderNo);   // @Cacheable 完全不生效
    ...
}
```

`this` 指向的是原始对象，不是代理，注解一行都不执行，跟不加 `@EnableCaching` 一样的静默形态。区别是这次静默发生在「别的注解都工作」的工程里，同一个方法从 Controller 进来时命中缓存、在内部调用时不命中，排查方向会被带偏到「缓存键是不是不对」上去，我就是被带到沟里转了一圈才回过神。

修法就三个字，拆出去。把被缓存的方法挪到另一个 bean 里，让调用跨过代理边界。`AopContext.currentProxy()` 也能救急，但那需要额外开启 exposeProxy，写出来的代码也更丑，不如一开始就把读缓存的查询方法放进独立的查询服务，本来这也更符合职责划分。

这段话在这个系列里出现第二次了，事务一次、缓存一次。只要是 AOP 实现的横切能力，这条都成立。

### dbHits，用数字当裁判

工程里有一个 `AtomicInteger` 计数器，每真实执行一次查库就加一，`GET /api/stats` 随时可以读。

加它不是为了凑接口。声明式缓存的麻烦在于「缓存生效」这个事实被 AOP 藏起来了，看不到命中，也看不到未命中，响应时间能感知但不精确。有一个数字摆在那里，第一次查询涨到 1，第二次查询还是 1，不需要任何信仰就能确认缓存在工作。

### 缓存放 Redis，还是放本地

这里插一段选型的话，教程不展开实现，但这个判断绕不过去。

Spring Cache 的抽象不关心 CacheManager 底下是什么，换成 Caffeine 就是本地缓存，换成 Redis 就是集中式缓存，业务注解一行不用改，这是这套抽象设计的卖点。但抽象的底下，两种形态的脾气完全不同。

本地缓存的读写是纳秒级的，没有网络，没有序列化，快到没有对比的意义。代价是每个实例各存一份，改了数据，别的实例的缓存还是旧的，实例越多不一致越明显，清缓存还得挨个实例清。

Redis 缓存正好反过来，所有实例共享一份，写一次全集群可见，清一次全集群干净，数据还能在应用重启后活下来。代价是每一次读写都过网络、过序列化，本篇的实测里命中一次 8 毫秒，听起来很快，跟本地缓存的纳秒比差着五个数量级。

怎么选，我的经验是一句话：看数据的新鲜度要求和实例数量。单实例应用不需要 Redis 缓存，Caffeine 就够了，别为了架构图好看多引一个中间件。多实例、数据允许秒级不一致、需要在重启后保命的，上 Redis。两个都要的，Spring Cache 也支持多级缓存组合，那是进阶玩法，先把这篇里的单级玩明白。

## 完整案例，跑起来看

打包起服务，真实 Redis 在树莓派上跑着。

创建订单，201 返回

```json
{"orderNo":"SO00000000001","product":"机械键盘","amount":399.00,
 "status":"CREATED","createdAt":"2026-09-19T09:00:11.644721200Z"}
```

第一次查询，未命中，走方法体，0.726 秒（含 JVM 冷启动开销）

```text
HTTP 200 time:0.726336s
```

第二次查询，命中缓存，8 毫秒

```text
HTTP 200 time:0.008414s
```

![](https://static.xiongneng.me/cache-first-vs-second-query-20260919170016.png)

90 倍的差距摆在这里，我再去看 stats，两次查询之后 `dbHits` 是 1。这个数字是这一篇的核心铁证：第二次查询没有走到数据库。

支付，然后立刻再查

```text
POST /api/orders/SO00000000001/pay    → 200, status PAID
GET  /api/orders/SO00000000001        → 200, time:0.006906s, status PAID
```

支付已经把缓存替换成 PAID 了，这次查询 7 毫秒，方法体没执行，库里也没走。`@CachePut` 的同步语义，实测如此。

这时候我去 Redis 里直接看键，正好能看到一次完整写入之后的形态。键名带着前缀和缓存名

```text
sb4:cache:orders::SO00000000001
```

值是带类型信息的 JSON

```json
{"@class":"com.xncoding.cache.domain.Order","orderNo":"SO00000000001",
 "product":"机械键盘","amount":["java.math.BigDecimal",399.00],
 "createdAt":"2026-09-19T09:00:11.644721200Z",
 "payTime":"2026-09-19T09:00:16.471339300Z","status":"PAID"}
```

![](https://static.xiongneng.me/cache-serialization-raw-20260922002844.png)

三个细节都是前面讲过的东西落到了实处。`@class` 指向领域对象，读回时靠它还原类型。`amount` 的类型 id 写成了**数组**形式，`["java.math.BigDecimal", 399.00]`，Jackson 的 wrapper array 风格，第一个元素是类型、第二个是值，白名单补成三条就是被它逼的。TTL 实测 596 秒，10 分钟减去从写入到查询之间过去的几秒，per-cache 定制的 10 分钟生效，yml 里的全局 30 分钟没有参与，替换的优先级也验证了。

![](https://static.xiongneng.me/cache-evict-reload-20260919170021.png)

再跑一遍取消流程。创建第二个订单 `SO00000000002`（显示器支架，129.00），先查一次让它进缓存（这一查 dbHits 涨到 2），然后取消，然后再查

```text
POST /api/orders/SO00000000002/cancel → 200, CANCELLED
GET  /api/orders/SO00000000002        → 200, 约 0.33s
GET  /api/stats                       → {"dbHits":3,"orderCount":2}
```

取消把缓存条目删了，这次查询变回 300 毫秒级。最终 dbHits 停在 3，三个数字各有归属，订单一首查贡献 1，订单二首查和取消后重查各贡献 1。`@CacheEvict` 的效果，数字说话。

再跑一遍快照接口，它是 2 秒 TTL 的 `order-flash` 缓存。第一次查询未命中，874 毫秒（这次 JVM 冷启动的份额不小）；两秒内再查，10 毫秒；睡 2.3 秒等 TTL 过期，再查，324 毫秒，dbHits 从 1 涨到 2。

```text
GET /api/orders/SO00000000001/flash   → time:0.874043s   （未命中）
GET /api/orders/SO00000000001/flash   → time:0.010095s   （2 秒内，命中）
GET /api/stats                        → {"dbHits":1,"orderCount":1}
（睡 2.3 秒）
GET /api/orders/SO00000000001/flash   → time:0.323670s   （过期，重查）
GET /api/stats                        → {"dbHits":2,"orderCount":1}
```

同一个订单号，`orders` 缓存里的条目活 10 分钟，`order-flash` 里的活 2 秒，两次查询各管各的。per-cache 定制 TTL 的意义就在这，同一种数据在不同业务视角下的新鲜度要求是不同的，全局一个 TTL 表达不了这种差别。

异常语义也都过了一遍。查不存在的订单，404 加 RFC 9457

```json
{"type":"urn:problem-type:resource-not-found","title":"资源不存在",
 "status":404,"detail":"订单不存在: SO404","instance":"/api/orders/SO404",
 "resourceType":"订单","resourceId":"SO404"}
```

给已支付订单发取消请求，409，报文里写明业务原因

```json
{"type":"urn:problem-type:business-rule-violation","status":409,
 "detail":"只有待支付订单可以取消，当前状态: PAID"}
```

还有 400 的校验失败，`product` 报商品名不能为空，`amount` 报金额必须大于 0，逐字段列在 `errors` 里。骨架跟前面几篇完全一样，异常处理这部分写一次用一路。

## 这一篇的测试怎么写

十个用例，两类。

API 集成测试走 MockMvc，用 JUnit 的 `@Order` 串联场景，因为同一个订单的状态要流转，创建、查询、支付、取消是有先后依赖的。核心断言全是围着 dbHits 转的

```java
// 第二次查询：命中缓存，方法体不执行
mockMvc.perform(get("/api/orders/{orderNo}", payFlowOrderNo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CREATED"));
assertThat(statsDbHits()).as("第二次查询不应再查库").isEqualTo(hitsAfterFirst);
```

flash 缓存的 TTL 过期也测了，`order-flash` 配的 2 秒，睡 2.3 秒再查，断言 dbHits 涨 1。用真实的时钟等真实的过期，比 mock 时间可信。

另一类是 `CacheRawValueTest`，直读 Redis 断言值的形态。`StringRedisTemplate` 直接 GET 缓存键，断言值以 `{"@class":"com.xncoding.cache.domain.Order"` 开头、包含订单号，再断言 `orders` 键的 TTL 不超过 600 秒、`order-flash` 不超过 2 秒。这一类测试防的是「缓存看起来在工作，但里面的东西已经不对了」，序列化器白名单改坏、前缀丢失，都会最先死在这里。

测试 profile 把 Redis 逻辑库切到 2，跟主配置隔离，这个约定从上一篇延续下来的。

## 避坑指南

这一篇的坑，有四个是「写了不报错但功能不对」级别的，比抛异常的坑难抓得多。

**坑一，不加 @EnableCaching，注解静默失效。** 容器正常启动，请求正常响应，方法每次都真实执行。没有任何报错、没有任何日志。防线是一个「查两次看计数」的测试，和 code review 时盯着配置类上的那行注解。

**坑二，两个 RedisCacheConfiguration 同名不同包。** Boot 的自动配置类在 `org.springframework.boot.cache.autoconfigure`，需要的 DSL 在 `org.springframework.data.redis.cache`。import 选错编译就挂，倒是不藏，但报错不提示该选哪个。

**坑三，序列化白名单只放行领域类型，读缓存必炸。** `BigDecimal` 这类非 final 的 JDK 值类型也会被 default typing 写入类型 id，读回时过不了白名单。现象是第一次写入成功、之后每次读都抛 `SerializationException`。把领域对象里用到的非 final JDK 类型包都加进 `allowIfSubType`。

**坑四，per-cache 配置丢掉 yml 的 key-prefix。** `withCacheConfiguration` 用全新的 `defaultCacheConfig()` 整体替换全局默认，yml 里的前缀带不过来。要么在 per-cache 配置里 `prefixCacheNameWith` 补上，要么接受裸键名。建议前者，键的前缀是排障时人眼定位的依据。

**坑五，@CachePut 返回 null 或 void。** 返回值会整个写入缓存，null 也照写。下次查询命中一个 null，业务拿到的就是空对象。`@CachePut` 方法必须返回完整的新状态。

**坑六，同类自调用不走代理。** `this.getOrder(...)` 绕过 AOP 代理，注解静默失效，形态比坑一更迷惑，因为同一个方法从外部调用是正常的。把被缓存的方法拆到独立的 bean 里，别在同类里绕过代理。

**坑七，多个缓存名不同步。** 工程里 `orders` 更新了，`order-flash` 还留着 2 秒前的旧值，这是设计使然不是 bug。但它提醒一点，缓存名一旦多起来，「数据在哪些缓存里各有一份、各自的 TTL 是多少、写路径要同步几个」需要一份清单盯着，记在脑子里不如记在配置类的注释里。

## 小结

声明式缓存适合的场景很明确：缓存逻辑想从业务代码里整体拿走，读写删的策略想收在一个配置类里统一治理。我的用法是读路径 `@Cacheable` 加 `sync = true` 起步，写路径 `@CachePut` 保持同步，删除 `@CacheEvict`，再配一个「查两次看计数」的测试当裁判，静默失效这类问题只有数字能裁决。缓存放本地还是放 Redis，看实例数量和数据的新鲜度要求：单实例 Caffeine 就够，多实例、要在重启后保命的再引 Redis，别为了架构图好看多背一个中间件。

有两件事这次没做成。一是多级缓存的组合没碰，本地加 Redis 两级之间的一致性怎么维持，我手上还没有实测数字，等工程里真用上了我再补测。二是 `allEntries` 全清的雪崩窗口只给了定性提醒，没有压测数据佐证，量大的系统先按分批清理处理，别拿生产流量去赌。

## 参考链接

- [Spring Framework 7.0 - 缓存抽象](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [Spring Boot 4.1 Reference - Caching](https://docs.spring.io/spring-boot/4.1/reference/io/caching.html)
- [Spring Data Redis - Redis Cache](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html)
- [Caffeine 官方仓库](https://github.com/ben-manes/caffeine)
