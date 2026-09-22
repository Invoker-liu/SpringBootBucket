# springboot4-redis

Spring Boot 4 教程第 07 篇配套工程：在 Boot 4.1.1 里把 Redis 当"带 TTL 的内存数据库"用，
覆盖连接配置、`RedisTemplate` 定制、Jackson 3 JSON 序列化、TTL 与原子操作，全部跑在真实 Redis 8.0 上。

对应文章：《SpringBoot4系列07 - Redis数据库》。

## 一、这个工程演示了什么

以「验证码」和「访问统计」两个最小场景，演示 Redis 在业务里的四种典型用法：

| 场景 | Redis 用法 | 对应命令 |
|---|---|---|
| 验证码 5 分钟有效 | 带 TTL 的写入，过期自动删除 | `SET ... EX` / `TTL` |
| 60 秒内禁止重发 | 不存在才写入（分布式语义的互斥标记） | `SET NX` |
| 错 5 次作废 | 读-改-写 + 保持剩余 TTL | `GET` / `SET` / `TTL` |
| 访问计数 | 原子自增 | `INCR` |
| 当日去重访客 | 集合去重计数 | `SADD` / `SCARD` |

除了 Web 层，还有专门展示序列化的部分：

- **`RedisTemplate<String, Object>` 定制**（`RedisConfig`）：key 用字符串，value 用
  `GenericJacksonJsonRedisSerializer`（Jackson 3）。自动配置给的默认模板是 JDK 序列化，
  写进 Redis 是二进制乱码，教程工程一律不用。
- **带多态类型信息的 JSON**：value 写进 Redis 是带 `@class` 字段的 JSON 文本，
  redis-cli 可直读，跨语言可解析；读回时按类型还原成 `CaptchaRecord`。

## 二、环境要求

| 项 | 要求 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Redis | 服务端 5.x~8.x 均可（本篇实测 8.0.6） |

## 三、准备 Redis

本机或内网起一个 Redis 即可，不需要建库建表——这是和关系型/MongoDB 篇最大的差别，
Redis 连"库"都只有 16 个逻辑编号（0~15）。

默认配置指向 `192.168.1.97:6379`（树莓派容器），带密码。改成你自己的：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: 你的密码        # 没有密码就整行删掉
```

或者用环境变量覆盖：`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。

**属性前缀是 `spring.data.redis.*`。** Boot 2 时代的 `spring.redis.*` 自 3.0.0 起废弃，
废弃级别是 error——写上去启动直接失败，不是告警。

### 测试用独立逻辑库

`src/test/resources/application-test.yml` 把 `database` 切到 1，与主配置的 0 号库
物理隔离，测试数据随便造。这也是 16 个逻辑库最合适的用途：环境隔离。

## 四、运行

```bash
# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn package
java -jar target/springboot4-redis-1.0.0.jar
```

启动后服务在 `http://localhost:8080`。Spring Data Redis 的自动配置里带一个
健康检查（`DataRedisHealthContributorAutoConfiguration`，属性
`management.health.redis.enabled`），不过本工程没引入 actuator starter，
所以不会暴露 `/actuator/health`，只是说明这套自动配置的拼图里有什么。

## 五、接口清单

### 验证码（key 设计：`captcha:{email}`，TTL 300s；冷却标记 `captcha:limit:{email}`，TTL 60s）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/captchas` | 发送验证码。60 秒冷却（429），5 分钟有效 |
| POST | `/api/captchas/verify` | 校验。正确销毁（200），错误累计次数，错满 5 次作废（403） |
| GET | `/api/captchas/{email}` | 查状态：剩余秒数、剩余尝试次数。不存在/已过期 → 404 |
| DELETE | `/api/captchas/{email}` | 手动作废。204；不存在 → 404 |

### 访问统计（key 设计：`stats:visits:total` 计数器；`stats:visitors:{date}` 当日访客集合）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/stats/visits?email=x` | 记一次访问并返回统计；带 email 参与当日去重 |
| GET | `/api/stats/visits/peek` | 只读统计，不产生访问记录 |

错误响应统一 RFC 9457 `application/problem+json`（`spring.mvc.problemdetails.enabled=true`）。

## 六、手工测试

```bash
# 发送
curl -X POST http://localhost:8080/api/captchas \
  -H "Content-Type: application/json" -d '{"email":"me@example.com"}'
# 201 {"email":"me@example.com","expiresInSecond":300,"resendAfterSecond":60}

# 60 秒内重发：429 Too Many Requests
# 状态
curl http://localhost:8080/api/captchas/me@example.com
# 200 {"email":"me@example.com","remainingSecond":287,"remainingAttempts":5,...}

# 校验（码从 Redis 里读：redis-cli GET "captcha:me@example.com"）
curl -X POST http://localhost:8080/api/captchas/verify \
  -H "Content-Type: application/json" -d '{"email":"me@example.com","code":"483920"}'
# 200 {"email":"me@example.com","verified":true}

# 统计
curl "http://localhost:8080/api/stats/visits?email=me@example.com"
```

redis-cli 里直读验证码，能看到 JSON 序列化的实际样子：

```
GET captcha:me@example.com
{"@class":"com.xncoding.redis.domain.CaptchaRecord","code":"483920","attempts":0,"createdAt":"2026-09-19T06:54:51.070586600Z"}
```

## 七、三个值得单独说的坑

### 1. `GenericJacksonJsonRedisSerializer` 没有无参构造

Jackson 2 时代 `new GenericJackson2JsonRedisSerializer()` 一把梭；Jackson 3 版本
必须走 builder，并且**要不要多态类型信息由你显式决定**：

```java
GenericJacksonJsonRedisSerializer.create(b -> b.enableDefaultTyping(typeValidator));
```

`enableUnsafeDefaultTyping()` 也在，但名字已经劝退了——默认类型信息不带白名单，
是反序列化攻击的经典入口。工程里用 `BasicPolymorphicTypeValidator` 只放行
`com.xncoding.` 开头的类型。

### 2. record 进不了多态通道

`CaptchaRecord` 故意用普通类。record 是 final 类，默认类型信息（`@class`）只对
非 final 类型生效——record 序列化后不带类型标记，读回来就退化成 `LinkedHashMap`，
然后你在 `instanceof CaptchaRecord` 上撞 NPE 或类型异常。要么用普通类，
要么自己注册类型解析。

### 3. 不带 TTL 的 set 会把过期时间清掉

校验失败要把累加后的记录写回去。直接 `set(key, record)` 会把键变成**永久键**，
一个本该 5 分钟过期的验证码永远留在 Redis 里。写回时必须把剩余 TTL 一起带上
（工程里是 `getExpire` 读剩余秒数再 `set(key, record, Duration.ofSeconds(remaining))`）。

### 4. commons-pool2 不是传递依赖

配 `spring.data.redis.lettuce.pool.*` 的前提是 classpath 上有 commons-pool2，
pom 里已显式引入。不引入的话池配置静默不生效，连接池永远不建。

## 八、运行测试

```bash
mvn test
```

- `CaptchaApiIntegrationTest`：端到端 API 测试（`@SpringBootTest` + `@AutoConfigureMockMvc`），
  走真实 Redis，测试邮箱带运行级后缀互不干扰，收尾清理本次键并回退全局计数。
- `RedisSerializationSliceTest`：`@DataRedisTest` 切片（新包名
  `org.springframework.boot.data.redis.test.autoconfigure`），验证 JSON 序列化往返、
  原始值可读、TTL、INCR 原子性。

Redis 连不上时测试整体 skip（Assumptions），不做"一片红"。

## 九、版本清单（实测）

| 项 | 版本 |
|---|---|
| Spring Boot | 4.1.1（parent） |
| Spring Framework | 7.0.9 |
| spring-data-redis | 4.1.1 |
| lettuce-core | 7.5.2.RELEASE |
| Jackson 3（tools.jackson） | 3.1.5 |
| commons-pool2 | 2.13.1 |
| Redis 服务端 | 8.0.6 |

## 许可

MIT © Xiong Neng
