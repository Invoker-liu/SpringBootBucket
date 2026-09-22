# springboot4-cache

Spring Boot 4 教程第 08 篇配套工程：在 Boot 4.1.1 里用声明式缓存注解
（`@Cacheable` / `@CachePut` / `@CacheEvict`）给订单查询提速，缓存落在 Redis 上，
覆盖缓存管理器定制、Jackson 3 JSON 序列化、每缓存名独立 TTL，全部跑在真实 Redis 8.0 上。

对应文章：《SpringBoot4系列08 - 使用缓存》。

## 一、这个工程演示了什么

以「订单」一个场景，演示 Spring Cache 抽象的三种核心注解：

| 注解 | 方法 | 效果 |
|---|---|---|
| `@Cacheable` | `getOrder` / `getOrderFlash` | 先查缓存，命中就不进方法体；未命中执行方法并写缓存 |
| `@CachePut` | `pay` | 方法一定执行，返回值直接替换缓存（写库后同步缓存） |
| `@CacheEvict` | `cancel` / `evictAllOrders` | 删缓存条目（单 key / `allEntries` 全清） |

"缓存是否生效"不看感觉，看两个硬指标：

- **dbHits 计数器**（`GET /api/stats`）：`@Cacheable` 命中时方法体不执行，计数不涨；
- **响应耗时**：未命中约 300ms（模拟慢查询），命中 10ms 以内，实测 0.700s → 0.008s。

两个缓存名演示"不同缓存不同策略"：

| 缓存名 | TTL | 用途 |
|---|---|---|
| `orders` | 10 分钟 | 订单主数据 |
| `order-flash` | 2 秒 | 订单快照（短命数据），过期后自动重查 |

## 二、Boot 4 的三个关键事实（S1 制品取证）

1. **starter 极简**：`spring-boot-starter-cache` = `spring-boot-starter` +
   `spring-boot-cache`（自动配置）+ `spring-context-support`。它不带任何缓存实现——
   想用 Redis 当缓存，工程里必须同时有 `spring-boot-starter-data-redis`。
2. **模块拆分**：自动配置类搬进 `spring-boot-cache`，新包名
   `org.springframework.boot.cache.autoconfigure`；专属测试 starter
   `spring-boot-starter-cache-test`（`@AutoConfigureCache` 在
   `org.springframework.boot.cache.test.autoconfigure`）。属性前缀没改名，仍 `spring.cache.*`。
3. **默认 JDK 序列化没变**：字节码实证 Redis 缓存管理器默认
   `JdkSerializationRedisSerializer`。要 JSON 得自己注册
   `RedisCacheManagerBuilderCustomizer`（本工程 `CacheConfig` 的做法）。

## 三、必须注意的坑（工程代码里都有注释）

1. **`@EnableCaching` 要自己加**。不加的话 `@Cacheable` 等注解被**静默忽略**——
   没有代理、没有报错，方法照常每次执行。CacheAutoConfiguration 挂着
   `@ConditionalOnBean(CacheAspectSupport.class)`，不开注解驱动连 CacheManager 都没有。
2. **两个 `RedisCacheConfiguration` 同名不同包**。要 import 的是
   `org.springframework.data.redis.cache.RedisCacheConfiguration`（配序列化/TTL 的 DSL）；
   Boot 自己的自动配置类在 `org.springframework.boot.cache.autoconfigure`，别选错。
3. **序列化白名单不止放行领域类型**。`BigDecimal` 这类非 final 的 JDK 值类型也会被
   default typing 写入类型 id，读回时同样要过 `PolymorphicTypeValidator`——只放行
   `"com.xncoding."` 的话，写缓存成功、读缓存必炸（SerializationException）。
4. **per-cache 配置会丢掉 yml 的 key-prefix**。`withCacheConfiguration` 里新
   `defaultCacheConfig()` 整体替换全局默认，前缀要在 per-cache 配置里再
   `prefixCacheNameWith(...)` 一次，否则键退化成裸的 `orders::<key>`。
5. **缓存对象用普通类不用 record**（07 篇同款结论）：走 default typing 的 JSON
   需要无参构造 + 可变字段，record 进不了多态通道。

## 四、运行

```bash
mvn spring-boot:run
```

Redis 连接改 `application.yml` 的 `spring.data.redis.*`，或用环境变量
`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。

### 体验缓存效果

```bash
# 创建订单
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" -d '{"product":"机械键盘","amount":399.00}'

# 第一次查询：约 0.7s（真实查库，写缓存）
curl -w "%{time_total}s\n" http://localhost:8080/api/orders/SO00000000001

# 第二次查询：约 0.008s（命中缓存，方法体没执行）
curl -w "%{time_total}s\n" http://localhost:8080/api/orders/SO00000000001

# 查库次数：两次查询只涨了 1
curl http://localhost:8080/api/stats

# 支付（@CachePut 同步缓存）后立刻再查：读到 PAID，依旧不查库
curl -X POST http://localhost:8080/api/orders/SO00000000001/pay
curl http://localhost:8080/api/orders/SO00000000001

# 取消（@CacheEvict 删缓存）后再查：重新走库，变慢
curl -X POST http://localhost:8080/api/orders/SO00000000001/cancel
curl -w "%{time_total}s\n" http://localhost:8080/api/orders/SO00000000001

# 快照接口：order-flash 缓存只有 2 秒 TTL
curl http://localhost:8080/api/orders/SO00000000001/flash
```

### 直接观察 Redis 里的键

```
sb4:cache:orders::SO00000000001        # TTL 600s，值是带 @class 的 JSON
sb4:cache:order-flash::SO00000000001   # TTL 2s
```

用 `redis-cli`（或任何客户端）GET 第一个键，看到的是可读 JSON 而非二进制——
这就是 CacheConfig 里自定义序列化器的效果。

## 五、测试

```bash
mvn test
```

- `OrderCacheIntegrationTest`：缓存全生命周期 HTTP 集成测试（命中不查库、
  CachePut 同步、CacheEvict 重查、flash TTL 过期、404/409/400 语义），10 个用例；
- `CacheRawValueTest`：直读 Redis 断言值的 JSON 形态与各缓存名的 TTL；
- 测试走 `test` profile（`application-test.yml`，逻辑库 2），与主配置隔离；
  Redis 不可达时自动跳过而不是误报。

## 六、许可

MIT © Xiong Neng
