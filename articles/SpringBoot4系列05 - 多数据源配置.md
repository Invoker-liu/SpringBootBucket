---
title: SpringBoot4系列05 - 多数据源配置
slug: sb4-multisource
date: 2026-09-21 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, 多数据源, DataSource, MyBatis-Plus ]
draft: false
---

第二篇那个订单工程，数据源是启动时自动配好的那一个：yml 里写一句 `spring.datasource.url`，Boot 就照着它建好 `HikariDataSource`、`SqlSessionFactory` 和事务管理器，一行配置类都不用写。

这一篇我把那个工程拆开了：交易库放订单和订单明细，运营库放商品主数据，两个库在同一个应用里共存。难的从来不是「怎么配两个数据源」，难的是配完之后那串莫名其妙的报错：

注入 `JdbcTemplate`，报 `NoSuchBeanDefinitionException`。

注入 `PlatformTransactionManager`，同样的报错。

最离谱的是注入 `OrderMapper`，还是同样的报错，而报错信息指向 mapper 自己，跟数据源看不出任何关系。

这些都不是 bug，是 Boot 4 主动退让的结果，所有退让条件里都有同一个注解 `@ConditionalOnSingleCandidate`，它检查的是「候选人是不是只有一个」。想明白这一条，多数据源就从「照抄配置」变成了「知道每个 bean 为什么存在」。

还有一个坑更隐蔽，我单独留了一节：**事务管理器绑错数据源的时候，异常照抛、事务照回滚，数据却留在库里**。没有报错，没有 WARN，日志上还写着「事务回滚成功」。

工程在 `springboot4-multisource` 目录下，结论全部来自真实运行，包括那些像段子的反例。

![](https://static.xiongneng.me/one-app-two-databases-20260921230425.png)

## 先把两个库的关系摆清楚

多数据源要回答的问题就三个：两个库是什么关系（主从、分库还是多租户）、选库发生在什么时候、事务要不要跨库。最后一问属于 JTA 或者最终一致性的范畴，这篇只指路，两套代码对应前两问：

| 方案 | 选库时机 | 库的关系 | 典型场景 |
|---|---|---|---|
| 静态多数据源 | 依赖注入时，编译期就定了 | 平级，各管一摊 | 报表库、运营库、读写分离的从库 |
| 动态路由数据源 | 运行时，按注解 | 平级，但可以随时换 | 分库、按业务切库、多租户 |

演示用的两个库是这么分工的：

| 库 | 装什么 | 谁在用 |
|---|---|---|
| `springboot4_pos` | `t_order`、`t_order_item` | `OrderMapper` |
| `springboot4_biz` | `t_product` | `ProductMapper` |

两个库各有一张结构完全一样的 `t_user`，id 都是 1，只有数据不同：交易库里叫 `admin`，运营库里叫 `admin1`。我造这张重复的表，是因为切错库时只会报「表不存在」，分不清是路由配错了还是脚本没跑；同名表加不同数据，把问题压缩成一次字段值的比对。

探针 SQL 里我还带了 `DATABASE()` 和 `CONNECTION_ID()`：

```sql
SELECT id, username, real_name,
       DATABASE()      AS databaseName,
       CONNECTION_ID() AS connectionId
FROM t_user WHERE id = 1
```

这两个是**连接**的属性，不是数据的属性，哪怕有人把两个库的数据改成一样也骗不了人。下面所有「切没切对」的判断，我认的都是 `DATABASE()`。

## 4.1.1 的版本事实

版本先查清楚，`spring-boot-starter-parent:4.1.1` 锁的是这些：

| 组件 | 版本 |
|---|---|
| Spring Framework | 7.0.9 |
| `spring-boot-jdbc` | 4.1.1 |
| `spring-boot-transaction` | 4.1.1 |
| HikariCP | 7.0.2 |
| MyBatis-Plus | 3.5.17（`mybatis-spring` 4.0.0、`mybatis` 3.5.19） |
| MySQL | 8.0+（本工程在 8.4 上验证） |

### JDBC 自动配置的包名

`spring-boot-jdbc-4.1.1.jar` 里的 `AutoConfiguration.imports` 一共 9 项，全部落在 `org.springframework.boot.jdbc.autoconfigure`，关键是这几条：

```
org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration
org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration
```

import 里写 `org.springframework.boot.autoconfigure.jdbc.*` 会直接编译失败，我试过。事务那边同理，三项都在 `org.springframework.boot.transaction.autoconfigure` 和 `.jta.autoconfigure` 下面：Boot 4 把自动配置按技术模块拆成独立的 jar，包名跟着模块走。排错时还有一条，`@AutoConfigureAfter` 指到不存在的类，编译直接失败。

### `spring.datasource.*` 下 12 个 error 级废弃属性

翻开 `spring-boot-jdbc` 的配置元数据，`spring.datasource` 命名空间下有 12 个标着 `level: error` 的废弃项：

| 废弃属性 | 换成 |
|---|---|
| `spring.datasource.continue-on-error` | `spring.sql.init.continue-on-error` |
| `spring.datasource.data` | `spring.sql.init.data-locations` |
| `spring.datasource.data-password` | `spring.sql.init.password` |
| `spring.datasource.data-username` | `spring.sql.init.username` |
| `spring.datasource.initialization-mode` | `spring.sql.init.mode` |
| `spring.datasource.jmx-enabled` | `spring.datasource.tomcat.jmx-enabled` |
| `spring.datasource.platform` | `spring.sql.init.platform` |
| `spring.datasource.schema` | `spring.sql.init.schema-locations` |
| `spring.datasource.schema-password` | `spring.sql.init.password` |
| `spring.datasource.schema-username` | `spring.sql.init.username` |
| `spring.datasource.separator` | `spring.sql.init.separator` |
| `spring.datasource.sql-script-encoding` | `spring.sql.init.encoding` |

`level: error` 不是「打个 WARN 还能用」，是**绑定时直接抛异常、启动即失败**，多数据源建表的常见错误写法正好撞在这上面：

```yaml
spring:
  datasource:
    schema: classpath:schema-pos.sql
    data: classpath:data-pos.sql
```

这样写启动第一步就过不去，改成 `spring.sql.init.*` 也还是不行，原因在建表那一节说。另外记一笔，这个命名空间下**没有任何** `multi`、`primary` 之类的多数据源属性，多数据源从头到尾都要手工搭。

### `spring.datasource.connection-fetch` 管不了时序问题

Boot 4 提供 `spring.datasource.connection-fetch`，取值 `eager`（默认）和 `lazy`：

| 值 | 官方说明 |
|---|---|
| `eager` | 直连，立刻取连接 |
| `lazy` | 尽可能晚取，没有语句执行就完全不取 |

`lazy` 看起来像能解决「事务里切换不生效」，不行：它作用于**自动配置出来的那个** `DataSource`，本篇全部手工声明数据源，配了也不生效，而且不能按请求切换，详见坑十四。

## 依赖和配置

依赖六项：`spring-boot-starter-webmvc`、`spring-boot-starter-aspectj`、`spring-boot-starter-jdbc`、`mybatis-plus-spring-boot4-starter`、`mysql-connector-j`（runtime）、`spring-boot-starter-actuator`，完整 pom 在工程里，说我的取舍。

Web starter 用 `spring-boot-starter-webmvc`。AOP starter 用 `spring-boot-starter-aspectj`，`spring-boot-starter-aop` 这个名字在 Boot 4 里不存在；这个依赖不是可选的，没有它 `@Aspect` 不会被识别，我吃过这个亏，`@DataSource` 在那儿躺着，切库就是不起作用。

`spring-boot-starter-jdbc` 非常薄：`starter + spring-boot-jdbc + HikariCP`，里面**没有** `spring-tx` 的显式坐标，它是 `spring-boot-jdbc` 和 mybatis-plus 传递进来的，本篇所有自动配置结论都出自 `spring-boot-jdbc`。MyBatis-Plus 必须用带 `boot4` 后缀的 starter，第二篇定过纪律。actuator 也不是摆设：里面有一个专门给路由数据源写的健康检查贡献者，`/actuator/health` 会把每个 target 分别展开，是「两个库到底连上没有」最省事的证据源。

然后看 yml，biz 段和 pos 段结构完全相同，只是库名换成 `springboot4_biz`、连接池名换成 `biz-pool`：

```yaml
server:
  port: ${SERVER_PORT:8080}

app:
  datasource:
    pos:
      url: jdbc:mysql://${DB_HOST:192.168.1.97}:${DB_PORT:3306}/${DB_NAME_POS:springboot4_pos}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
      username: ${DB_USER:root}
      password: ${DB_PASSWORD:root123456}
      driver-class-name: com.mysql.cj.jdbc.Driver
      hikari:
        pool-name: pos-pool
```

数据源属性是**两层**的写法：`app.datasource.pos.*` 绑 `DataSourceProperties` 装连接信息，`app.datasource.pos.hikari.*` 绑 `HikariDataSource` 自己装连接池参数，这是 Boot 官方文档给多数据源的推荐拆法，`pool-name` 能自己取名字，下面排查日志全靠它。

`spring.datasource.url` 我故意没写：容器里只要出现两个 `DataSource`，默认数据源那个自动配置就退让了，连接信息压根不会被读；而且那族属性里有一半已是 error 级废弃，写了直接启动失败。

## 方案 A：静态多数据源

先讲这个方案，理解原理的必经之路。

### 为什么必须全部手工写

注册第二个 `DataSource` 之后，下面这些 bean 全都不会出现，这张表是我对着容器一个一个验出来的：

| 自动配置 | 退让条件 | 多数据源下的后果 |
|---|---|---|
| `DataSourceAutoConfiguration` | `@ConditionalOnMissingBean(DataSource, XADataSource)` | 默认数据源不建（这个在预期内） |
| `JdbcTemplateAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | `JdbcTemplate`、`NamedParameterJdbcTemplate` 都没了 |
| `JdbcClientAutoConfiguration` | `@ConditionalOnSingleCandidate(NamedParameterJdbcTemplate)` | `JdbcClient` 跟着没了，连锁 |
| `DataSourceTransactionManagerAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | 没有 `PlatformTransactionManager` |
| `DataSourceInitializationAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | `schema.sql` / `data.sql` 不执行 |
| `MybatisPlusAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | **mapper 扫描整体失效** |
| `TransactionAutoConfiguration$TransactionTemplateConfiguration` | `@ConditionalOnSingleCandidate(PlatformTransactionManager)` | `TransactionTemplate` 没了 |

![](https://static.xiongneng.me/autoconfig-backoff-20260921230425.png)

MyBatis-Plus 那条最坑：mapper 扫描没了，所有 `@Mapper` 接口都不会注册成 bean，启动时报 `NoSuchBeanDefinitionException`，异常信息里出现的是 `OrderMapper`。我第一次就栽在这里，把包名、编译输出目录查了个遍，根因却在数据源那边。

退让全是静默的：不报错、不警告，只是 bean 没了，排查这类问题看日志没用，得看容器里到底有什么。`@ConditionalOnSingleCandidate` 的完整语义是「只有一个候选，**或者**有多个但恰好一个标了 `@Primary`」，两个平级的数据源让条件不成立，一串东西集体消失。这个「或者」很重要，坑十拿它做了对照实验。

### 一个库一套 bean

静态方案的核心就一句话：一个 `SqlSessionFactory` 是「数据源 + MyBatis 配置 + 映射文件」的整包，库不同这个整包就得新建一套。

![](https://static.xiongneng.me/static-vs-dynamic-20260921230425.png)

交易库的配置类长这样（建表用的 `DataSourceInitializer` 见下一节）：

```java
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.xncoding.multisource.mapper.pos",
        sqlSessionFactoryRef = "posSqlSessionFactory")
public class PosDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.pos.hikari")
    public HikariDataSource posDataSource(DataSourceProperties posDataSourceProperties) {
        return posDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public MybatisSqlSessionFactoryBean posSqlSessionFactory(
            @Qualifier("posDataSource") DataSource posDataSource) throws IOException {
        return MybatisFactorySupport.factory(posDataSource, "classpath*:/mapper/pos/*.xml");
    }

    @Bean
    public JdbcTransactionManager posTransactionManager(
            @Qualifier("posDataSource") DataSource posDataSource) {
        return new JdbcTransactionManager(posDataSource);
    }
}
```

`posDataSource` 注入属性类，`initializeDataSourceBuilder()` 拿它推断 url 和驱动。`@MapperScan` 指向的 `posSqlSessionFactory` 是工厂**生产出来的**那个 `SqlSessionFactory`，不是 `MybatisSqlSessionFactoryBean` 本身：后者是 `FactoryBean`，容器里注册的是它 `getObject()` 的产物。事务管理器我用子类 `JdbcTransactionManager`：父类 `commit()` 时数据库报错抛原生 `SQLException`，子类翻成 Spring 的 `DataAccessException`。### 建表归谁管

建表不能指望自动配置：`DataSourceInitializationAutoConfiguration` 同样有 `@ConditionalOnSingleCandidate(DataSource)`，而且 `spring.sql.init.schema-locations` 只能给一个值，回答不了「**这段脚本发给哪个库**」。所以每个库一个 `DataSourceInitializer`，各喂各的 schema 和 data 脚本。工程里那五份 SQL 脚本还有个副产品：单独执行就能重置数据，不用重启应用。

### `MybatisSqlSessionFactoryBean` 的包名

这是本篇第三个大坑，说实话是个纯粹的「抄教程会死」的坑：3.5.17 里这个类在 `com.baomidou.mybatisplus.spring.`，而网上讲「多数据源 + MyBatis-Plus」的文章基本都还写着 `extension.spring` 旧包名。我照抄过来第一个动作就是编译不过，照抄编译不过的，九成是这个原因。

这个类用来替代 MyBatis 原生 `SqlSessionFactoryBean`，三个工厂共用这段构造逻辑，我写成包私有的静态方法：

```java
static MybatisSqlSessionFactoryBean factory(DataSource dataSource, String mapperLocationPattern)
        throws IOException {
    GlobalConfig globalConfig = new GlobalConfig();
    globalConfig.setBanner(false);

    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setGlobalConfig(globalConfig);
    factory.setMapperLocations(
            new PathMatchingResourcePatternResolver().getResources(mapperLocationPattern));
    return factory;
}
```

`setGlobalConfig()` 那两行不能省：`BaseMapper` 那套 CRUD 靠 `GlobalConfig` 里的 `SqlInjector` 注入，不设的话只有空接口，表现是「找不到 statement」，报错方向又指歪了。

### 跑起来看

先把两个库建好灌好数据，起服务。

```bash
curl http://127.0.0.1:8080/api/static/snapshot
```

浏览器里打开这个接口，看到的就是这么一行，`posDatabase` 是 `springboot4_pos`、3 条订单，`bizDatabase` 是 `springboot4_biz`、4 个商品：

![](https://static.xiongneng.me/browser-snapshot-20260918211511.png)

它是这次运行的真实取值，不是截完再拼的；计数由种子脚本写死，反复跑都是这个结果。

交易库的 join 查询验证另一件事：两张表在同一个库里。

```bash
curl http://127.0.0.1:8080/api/static/orders/1
```

```json
{
  "createdAt": "2026-09-01T10:00:00",
  "customerName": "周伯通",
  "databaseName": "springboot4_pos",
  "id": 1,
  "items": [
    {
      "id": 1,
      "orderId": 1,
      "price": 299.00,
      "productName": "机械键盘",
      "quantity": 1
    }
  ],
  "orderNo": "POS20260901001",
  "status": "PAID",
  "totalAmount": 299.00
}
```

`databaseName` 是 `SELECT DATABASE()` 来的，专门给排查用。运营库那边换个 controller、换个 mapper，`/api/static/products` 返回的就是 `t_product` 的数据。

到这里静态方案就跑通了，没有任何技术难度，就是啰嗦。

### 一条反例：事务管理器绑错数据源

这一段是我最想单独拎出来说的。

```java
@Transactional("bizTransactionManager")     // ← 运营库的事务管理器
public void insertThenFailWithWrongManager(String orderNo) {
    orderMapper.insert(newOrder(orderNo));  // ← 写的是交易库
    throw new IllegalStateException("故意失败，观察交易库这一笔有没有回滚");
}
```

事务管理器绑的是运营库，写入的是交易库，然后抛异常。会发生两件事：`@Transactional("bizTransactionManager")` 让 Spring 去**运营库**拿连接、开事务、绑在当前线程上；而 `orderMapper.insert(...)` 用的是**交易库**自己的连接，那条连接不在任何事务里（Hikari 默认自动提交），方法返回前就已经落库了。紧接着抛异常，Spring 老老实实把运营库上那个空事务回滚了。

![](https://static.xiongneng.me/wrong-transaction-manager-20260921230425.png)

我跑了一遍：

```bash
curl -X POST 'http://127.0.0.1:8080/api/static/orders/transaction-demo?orderNo=DEMO-WRONG-1&manager=biz'
```

```json
{
  "scenario": "事务管理器绑错数据源（bizTransactionManager 写交易库）",
  "transactionManager": "bizTransactionManager",
  "writeTarget": "springboot4_pos",
  "exceptionMessage": "故意失败，观察交易库这一笔有没有回滚",
  "ordersBefore": 3,
  "ordersAfter": 4,
  "rolledBack": false,
  "explanation": "事务开在了 springboot4_biz 上，而 insert 走的是 springboot4_pos 自己的连接，不在那个事务范围内（自动提交）。所以事务确实回滚了，回滚的却是一个空的运营库事务，交易库这一笔留了下来。"
}
```

`ordersBefore` 是 3，`ordersAfter` 是 4。换成 `manager=pos`，同样的代码、同样的异常，`ordersAfter` 就回到 3 了：

```json
{
  "scenario": "事务管理器与写入目标一致（posTransactionManager 写交易库）",
  "transactionManager": "posTransactionManager",
  "writeTarget": "springboot4_pos",
  "ordersBefore": 3,
  "ordersAfter": 3,
  "rolledBack": true,
  "explanation": "事务管理器绑的就是交易库，insert 在那个事务里，异常触发回滚，订单数不变。"
}
```

**这个 bug 不报错、不打 WARN、日志上还显示「事务回滚成功」。** 要发现它，只能把调用前后的行数摆出来比，所以这个接口把两个计数一起返回。我直接查库确认过，那笔「回滚了但还在」的数据就躺在交易库里，id 是 **5** 不是 4：正确的那次演示也 `INSERT` 过一次、拿到了 `id=4`，随后被回滚了，MySQL 的 `AUTO_INCREMENT` 不会因为回滚而退回去，别拿自增空洞当「有没有丢数据」的依据。

结论：静态方案下每个 `@Transactional` 都得显式写事务管理器名字，而写错名字**不会有任何提示**。工程里三个事务管理器谁都没标 `@Primary`，写裸的 `@Transactional` 会直接抛 `NoUniqueBeanDefinitionException`，这是故意的，「这个事务管的是哪个库」本来就该被显式回答。

## 方案 B：动态路由数据源

静态方案太死了：加一个库动四个地方，切换发生在依赖注入阶段，运行时想换库做不到。动态方案换个思路：让 MyBatis 眼里始终只有一个数据源，库的切换发生在更下面一层。

### `AbstractRoutingDataSource` 没有废弃

这个类能用，而且是官方认可的一等用法。我把 `spring-jdbc-7.0.9.jar` 的字节码全量扫了一遍，`AbstractRoutingDataSource` **没有任何 `Deprecated` 标记**，签名也没变。更直接的证据在 Boot 4 这边，`spring-boot-jdbc` 里有一个专门为它写的东西：

```java
class DataSourceHealthContributorAutoConfiguration$RoutingDataSourceHealthContributor
        implements CompositeHealthContributor {

    RoutingDataSourceHealthContributor(AbstractRoutingDataSource,
                                       Function<DataSource, HealthContributor>);
}
```

而且 `DataSourceHealthContributorAutoConfiguration` 的条件里**显式列出了这个类**：

```
DataSourceHealthContributorAutoConfiguration.ConditionalOnClass =
    ConditionalOnEnabledHealthIndicator, JdbcTemplate, AbstractRoutingDataSource
```

一个已经被废弃的类是不会被写进自动配置条件里的，这就是我敢放心用它的铁证。对比之下，跨库链式事务的 `ChainedTransactionManager` 在 Boot 4 里位于 `spring-data-commons`，字节码里有 4 处 `Deprecated` 标记，别拿它当选型依据。

### 上下文用栈，不用单值

先定义逻辑库的枚举：

```java
public enum DataSourceKey {
    POS("springboot4_pos"),
    BIZ("springboot4_biz"),
    REPORT(null);          // 故意不注册，用来跑「标了没配的库」这条路径

    private final String physicalDatabase;

    DataSourceKey(String physicalDatabase) {
        this.physicalDatabase = physicalDatabase;
    }

    public String physicalDatabase() {
        return physicalDatabase;
    }
}
```

用枚举不用字符串，是因为路由数据源内部拿这个值当 `Map` 的 key，拼错了编译期查不出来。`REPORT` 的物理库是 `null`，故意没注册，是给反例留的入口。然后是这个方案的核心，一个 `ThreadLocal`：

```java
public final class DataSourceContextHolder {

    private static final ThreadLocal<Deque<DataSourceKey>> HOLDER =
            ThreadLocal.withInitial(ArrayDeque::new);

    public static void push(DataSourceKey key) {
        HOLDER.get().push(key);
    }

    public static void poll() {
        Deque<DataSourceKey> deque = HOLDER.get();
        deque.poll();
        if (deque.isEmpty()) {
            HOLDER.remove();
        }
    }

    public static DataSourceKey peek() {
        return HOLDER.get().peek();
    }
}
```

三个设计决定。**用 `ThreadLocal`，不用 `InheritableThreadLocal`**：可继承版本只在线程被创建那一刻拷一次父线程的值，Web 容器的线程池早就创建完了，表现出来就是「有时候对、有时候错、重启一下又好了」这种最难查的毛病。**用栈，不存单个值**：`@DataSource` 允许嵌套，外层标 BIZ 里层标 POS，里层跑完必须能回得来。**弹干净之后把 `ThreadLocal` 本身也 remove 掉**：池化线程反复复用，用完必须清。

### 路由数据源本体

```java
public class DynamicDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.peek();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        try {
            return super.determineTargetDataSource();
        } catch (IllegalStateException ex) {
            throw new DataSourceRoutingException(
                    "没有可用数据源：" + ex.getMessage()
                            + "。通常是没标 @DataSource，或者标注的标识没注册到 targetDataSources 里",
                    ex);
        }
    }
}
```

父类的 `determineTargetDataSource()` 是 `protected` 且**不是 final**，Framework 7 里实测可以覆写；我把泛泛的 `IllegalStateException` 翻成专用异常，全局异常处理器放心只接 `DataSourceRoutingException`。注册时有几个参数必须显式设置：

```java
@Bean
public DynamicDataSource dynamicDataSource(
        @Qualifier("posDataSource") DataSource posDataSource,
        @Qualifier("bizDataSource") DataSource bizDataSource) {
    Map<Object, Object> targets = new LinkedHashMap<>();
    targets.put(DataSourceKey.POS, posDataSource);
    targets.put(DataSourceKey.BIZ, bizDataSource);

    DynamicDataSource routingDataSource = new DynamicDataSource();
    routingDataSource.setTargetDataSources(targets);
    routingDataSource.setDefaultTargetDataSource(posDataSource);
    routingDataSource.setLenientFallback(false);
    return routingDataSource;
}
```

方法参数用 `@Qualifier`，容器里已经有两个 `DataSource`，按类型注入会歧义报错。`setLenientFallback(false)` 是最要紧的一行：默认值 `true` 的含义是「标识取不到就悄悄用默认库」，关掉之后，一个非 null 但没注册的 key 会直接抛异常。

![](https://static.xiongneng.me/routing-switch-20260921230425.png)

### 切面的顺序不能随手写

切数据源和开事务是两条互不知情的切面，谁先执行决定了结果对不对。Spring 的事务拿到连接就绑在当前线程上，所以「挑哪个库」必须在**取连接之前**做完。顺序反了的表现是：事务先开、连接从默认库借出来并绑定，切面再改标识，之后的 SQL 走的还是那条已经绑好的连接，**注解看起来生效了，实际没生效**。

Spring 的 advice 按 order 从小到大往外包，order 越小越靠外。Boot 4 里 `@EnableTransactionManagement` 的 advisor 用默认值 `Ordered.LOWEST_PRECEDENCE`，而 `TransactionAutoConfiguration` **没有暴露任何调整它的属性**，所以把顺序写在事务注解这一头是常见错误写法：

```java
@EnableTransactionManagement(order = 2)
```

Boot 4 里这个 `order` 属性**没有对应支持**，写上去不报错但也不起作用，只能从切面这一头压下去：

```java
@Aspect
@Component
@Order(DataSourceAspect.ORDER)   // = 0
public class DataSourceAspect {

    public static final int ORDER = 0;

    @Around("@annotation(com.xncoding.multisource.annotation.DataSource) "
            + "|| @within(com.xncoding.multisource.annotation.DataSource)")
    public Object switchDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        DataSourceKey key = resolveKey(joinPoint);
        DataSourceContextHolder.push(key);
        try {
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.poll();
        }
    }
}
```

写 `0` 不写 `Ordered.HIGHEST_PRECEDENCE`，是给别的切面留位置：以后日志切面、幂等切面想包在外面，还能用负数插进去。

![](https://static.xiongneng.me/aspect-order-20260921230425.png)

取注解的方式有一处细节：不能用 `joinPoint.getSignature().getDeclaringType()`，那是**接口**或者代理声明的方法，类上的 `@DataSource` 有可能取不到；要沿 `getTarget().getClass()` 的真实方法找，先方法注解、再类注解，类上标默认库、个别方法单独覆盖。

### 跑起来看：基本切换

```bash
curl http://127.0.0.1:8080/api/dynamic/probe/pos
```

```json
{
  "label": "标了 @DataSource(POS)",
  "expected": "POS",
  "actualDatabase": "springboot4_pos",
  "username": "admin",
  "realName": "交易库管理员",
  "connectionId": 8609
}
```

```bash
curl http://127.0.0.1:8080/api/dynamic/probe/biz
```

```json
{
  "label": "标了 @DataSource(BIZ)",
  "expected": "BIZ",
  "actualDatabase": "springboot4_biz",
  "username": "admin1",
  "realName": "运营库管理员",
  "connectionId": 8608
}
```

同一句 SQL、同一个 mapper、同一个 `SqlSessionFactory`，靠一个注解落到两个库上。两个 `connectionId` 不一样，说明这两次用的是两条不同的物理连接。一次请求里先后切两个库也行，`/api/dynamic/probe/both` 会按顺序返回 POS、BIZ 两条探针结果。

### 四个反例

正例谁写都能对，有价值的是下面四个「应该失败」的场景，全部做成了可以访问的接口，我一个一个跑的。

**反例一，标了一个没注册的库。** `REPORT` 在 `targetDataSources` 里没注册，标上它请求直接 500，`Content-Type` 是 `application/problem+json`，`detail` 里带着 `Cannot determine target DataSource for lookup key [REPORT]`：

```bash
curl http://127.0.0.1:8080/api/dynamic/probe/unregistered
```

![](https://static.xiongneng.me/probe-unregistered-20260918211513.png)

抛异常，不是静默回退到默认库，这就是 `setLenientFallback(false)` 换来的东西：默认的 `true` 会让这里一声不吭地返回交易库的数据，响应 200。不过这条还有个故事，坑十三讲。

**反例二，同类内部调用，注解被 AOP 吃掉。** 外层方法标 BIZ，中间插一步标了 `@DataSource(POS)` 的 `this.probePos()`，三步探针的结果：

```json
{
  "scenario": "selfInvocation",
  "expectation": "第 2 步应该切到 POS，整体结果 POS/BIZ/BIZ",
  "conclusion": "第 2 步没切过去，三步全在 BIZ",
  "steps": [
    { "label": "第 1 步：外层(B) 直接查", "expected": "BIZ", "actualDatabase": "springboot4_biz", "username": "admin1" },
    { "label": "标了 @DataSource(POS)", "expected": "POS", "actualDatabase": "springboot4_biz", "username": "admin1" },
    { "label": "第 3 步：回到外层(B)", "expected": "BIZ", "actualDatabase": "springboot4_biz", "username": "admin1" }
  ]
}
```

`@Transactional`、`@Cacheable`、`@Async` 全是同一个毛病，修法是把方法挪到另一个 bean 去，工程里那个 `ProbeInnerService` 就是为这个拆出来的。

**反例三，事务里面才切换，切不动。** 事务方法内部手动 `push(BIZ)` 再查询，两步都在 POS：

```json
{
  "scenario": "switchInsideTransaction",
  "expectation": "第 2 步应该切到 BIZ，整体 POS/BIZ",
  "conclusion": "第 2 步没切过去，两步都在 POS",
  "steps": [
    { "label": "第 1 步：事务已开，还没切", "expected": "POS", "actualDatabase": "springboot4_pos", "connectionId": 8609 },
    { "label": "第 2 步：事务内部切到 BIZ 之后", "expected": "BIZ", "actualDatabase": "springboot4_pos", "connectionId": 8609 }
  ]
}
```

两步的 `connectionId` 完全一样，`actualDatabase` 都是 POS：事务在方法进入时就绑好连接了，之后再改标识，改的是一个不会再被读的变量。对应的正例是两个注解标在同一个方法上，让切面顺序自己决定先后，落库是 `springboot4_biz`、`connectionId` 8608。

**这一对正反例是动态方案能不能用的分水岭。** 要切换，注解必须标在事务外面。

**反例四，换了个线程。** 方法里用 `CompletableFuture.supplyAsync(...)` 把第 2 步查询单独扔给另一个线程，结果：

```json
{
  "scenario": "probeInAnotherThread",
  "expectation": "第 2 步应该也在 BIZ",
  "conclusion": "第 2 步落回了 springboot4_pos（默认库），而且没有报错。ThreadLocal 不会跨线程传递，lenientFallback=false 对 null 标识也无效",
  "steps": [
    { "label": "第 1 步：当前线程(B)", "expected": "BIZ", "actualDatabase": "springboot4_biz" },
    { "label": "第 2 步：另一个线程里查", "actualDatabase": "springboot4_pos" },
    { "label": "第 3 步：回到当前线程(B)", "expected": "BIZ", "actualDatabase": "springboot4_biz" }
  ]
}
```

第 2 步落回了默认库，**而且没有报错**，接口返回 200。这一条推翻一个流传很广的说法：设了 `setLenientFallback(false)` 就不会有静默回退。它对「非 null 但没注册的 key」成立，换线程不一样，父类的源码是这么写的：

```java
DataSource dataSource = this.resolvedDataSources.get(lookupKey);
if (dataSource == null && (this.lenientFallback || lookupKey == null)) {
    dataSource = this.resolvedDefaultDataSource;
}
if (dataSource == null) {
    throw new IllegalStateException("Cannot determine target DataSource for lookup key [" + lookupKey + "]");
}
```

后半截那个 `lookupKey == null` 是**独立的一条路**，跟 `lenientFallback` 一点关系都没有：标识为 null 时无条件回退到默认数据源。换线程正好走这条路径，`ThreadLocal` 不过线程边界，新线程上 `peek()` 返回 null。结论：**想在新线程里用对库，只能把标识显式传进去，上下文不会自己过去。**

还有个细节：我本想覆写 `toString()` 让 Hikari 日志带上库名，`javap` 查下来父类链上没有现成的重写可用，最后靠切面的 DEBUG 日志代替。

### 切面日志和实际执行的 SQL

开了 DEBUG，每次切换都有记录：

```
2 切到数据源 [POS]，目标方法 ProbeService.probePos()
1 切到数据源 [REPORT]，目标方法 ProbeService.probeUnregisteredKey()
1 切到数据源 [POS]，目标方法 ProbeInnerService.probePos(..)
1 切到数据源 [BIZ]，目标方法 ProbeService.selfInvocation()
```

注意第 3 行，目标方法是 `ProbeInnerService.probePos(..)` 而不是 `ProbeService`，这就是「跨 bean 调用才生效」的证据；反例二那次 `selfInvocation` 只切了一次，内层的 `probePos()` 没有留下记录。

另一半证据在 SQL 这边：

```
SELECT COUNT(*) FROM t_order
SELECT COUNT( * ) AS total FROM t_product
SELECT id, username, real_name, DATABASE() AS databaseName, CONNECTION_ID() AS connectionId FROM t_user WHERE id = 1
```

`t_order` 和 `t_product` 的语句交替出现，说明在用两个库。但**光看 SQL 日志判断不了连的是哪个库**，同一条 `SELECT COUNT(*) FROM t_order` 在两个库上都成立，所以工程里把连接池名字分开取了，日志里 `biz-pool` 和 `pos-pool` 各自打出了 Starting 和 Start completed。

切没切对只认三样东西：`DATABASE()`、`CONNECTION_ID()`，还有切面那行 DEBUG。整轮验证下来应用日志 ERROR 0 行、WARN 0 行，四个反例全跑了一遍，一行 ERROR 都没打：这类 bug 最难缠的地方就是它们不制造噪音。

## 健康检查：两个库都连上了最省事的证明

最省心的一个发现是健康检查：`/actuator/health` 的 `components.db` 下面有四个条目，普通数据源 `posDataSource`、`bizDataSource` 平级并列，`dynamicDataSource` 下面**自己展开了 BIZ 和 POS 两个独立条目**，全部 `UP`：

```bash
curl http://127.0.0.1:8080/actuator/health
```

![](https://static.xiongneng.me/health-expanded-20260918211515.png)

上面这张是完整响应，还有磁盘、SSL、liveness 这些系统自带的检查项。没有为这个展开写任何代码，就是前面那个 `RoutingDataSourceHealthContributor` 干的活，键名直接用 `targetDataSources` 里的 key。它是「两个库都连上了」最省事的证明，还能发现 SQL 日志发现不了的问题：某个库的连接池配得不对，但业务上还没走到那条路径。

## 这一篇的测试怎么写

```bash
mvn test
```

```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `DynamicRoutingTest` | 9 | 路由切换、四个「应该失败」的反例 |
| `StaticDataSourceTest` | 6 | 静态绑定、join、事务管理器正反例 |
| `AutoConfigurationBackoffTest` | 5 | 断言一串自动配置的 bean **不存在** |
| `AdvisorOrderTest` | 2 | advisor 链上数据源切面排在事务切面之前 |
| `ExceptionHandlerTest` | 2 | 被包装过的路由失败仍然返回带明细的 problem+json |
| `PrimaryAutoConfigurationProbeTest` | 1 | 对照实验，一个 `@Primary` 让自动配置复活 |

`AutoConfigurationBackoffTest` 全是「某个 bean 不存在」的断言，写成断言比写成注释有用：注释会被忽略，测试会红。`AdvisorOrderTest` 锁的是一件「改了也不会报错」的事：它断言数据源切面的 order 必须小于事务 advisor、且在代理链上排在更外层，两个断言都写，是防哪天有人改了排序方式。取断言值时我踩到一个 API 细节：`Advisor` 接口本身**没有** `getOrder()`，是 `AspectJPointcutAdvisor` 这些具体实现各自实现了 `Ordered`，得先 instanceof 再调用。

另外，第四篇的切片测试靠 `@Transactional` 回滚保证用例互不干扰，这一篇**没法这么做**：事务管理器有三个，注入会歧义，而且要验证的恰恰是「事务有没有真的回滚」，用事务去清理事务等于把要测的东西当工具用。所以 `StaticDataSourceTest` 的清理放在 `@AfterEach` 里，用例数据统一用 `TEST-MS-` 前缀，跑完按前缀删掉。

## 避坑指南

**坑一，`@ConditionalOnSingleCandidate` 是这个主题的总开关。** 它检查的是「候选人是不是只有一个」，不是「有没有」。注册第二个数据源之后，`JdbcTemplate`、`JdbcClient`、事务管理器、`schema.sql` 初始化、整条 MyBatis-Plus 自动配置全线退让，全是静默的。排查别猜，直接写测试断言 bean 存不存在，比翻日志高效。

**坑二，`spring.datasource.*` 里有 12 个属性是 error 级废弃。** 绑定时直接抛异常、启动即失败，把 `spring.datasource.schema` 写进多数据源工程第一步就挂。改成 `spring.sql.init.*` 也没用，那个自动配置同样有 `@ConditionalOnSingleCandidate`，而且回答不了「这段脚本发给哪个库」。

**坑三，报错方向指错的时候要怀疑数据源。** 「找不到 `XxxMapper`」的第一反应是查 mapper 包名和编译输出目录；项目里配了多个数据源时，先确认 MyBatis-Plus 的自动配置生效了没有，它退让了，mapper 扫描是一起消失的。

**坑四，`MybatisSqlSessionFactoryBean` 的包名在 3.5.17 是 `com.baomidou.mybatisplus.spring`。** 写成 `extension.spring` 编译不过。另外 `setGlobalConfig()` 不能省，不设它 `BaseMapper` 那套 CRUD 注入不进去，报的是「找不到 statement」，同样指错方向。

**坑五，`@MapperScan` 是类级注解，一个类只能声明一组规则。** 所以每个库一个配置类，数据源、工厂、事务管理器、扫描规则管齐，三个库三个类，躲不掉。

**坑六，数据源属性要拆成两层前缀。** 连接信息绑 `DataSourceProperties`，连接池参数绑 `HikariDataSource`。`DataSourceProperties` 可以直接复用，`initializeDataSourceBuilder()` 里驱动推断那堆细节都是现成的。

**坑七，切面顺序错了，注解会「看起来生效了」。** 事务拿到连接就绑到线程上，挑库必须在取连接之前。Boot 4 里 `@EnableTransactionManagement(order = 2)` 这个属性**没有对应支持**，只能从切面这头压 `@Order`，工程里给 `0` 留出负数位置。这个顺序 Spring 不打印，工程里用测试把它固定住了。

**坑八，`@DataSource` 和 `@Transactional` 一样，自调用不生效。** 同类内部 `this.方法()` 不走代理，修法是把方法挪到另一个 bean，或者开 `@EnableAspectJAutoProxy(exposeProxy = true)` 后用 `AopContext.currentProxy()`。它比事务版更容易被忽略：数据源不对不报错，只是数据不对。

**坑九，`lenientFallback=false` 挡不住 null 标识。** 这一条和很多文章的说法相反。父类判断里的 `lookupKey == null` 是独立的一条路，标识为 null 时无条件回退到默认库，开关管不着。换线程正是这条路径：`ThreadLocal` 不过线程边界，新线程上 `peek()` 返回 null，查询静默落到默认库，接口返回 200。这个开关只管「非 null 但没注册」的情况。

**坑十，一个 `@Primary` 能让整串自动配置复活。** `@ConditionalOnSingleCandidate` 的完整语义是「只有一个候选，或者有多个但恰好一个标了 `@Primary`」。工程里有对照实验：`PrimaryAutoConfigurationProbeTest` 单独起一个只声明 `@Primary` 数据源的上下文，断言 `JdbcTemplate`、`JdbcClient`、`PlatformTransactionManager`、`SqlSessionFactory`、`TransactionTemplate` 全部复活。但要想清楚：自动配置只绑那一个数据源，第二个库它照样拿不到，只有动态方案下「那一个」本来就是路由数据源时才刚好成立。工程里两套方案并存，我选了「全部手工、谁都不标 `@Primary`」这条更直白的路。

**坑十一，三个事务管理器，`@Transactional` 不写名字直接报错。** 这是故意的，多数据源下「这个事务管的是哪个库」本来就该被显式回答。但静默的反面才可怕，看坑十二。

**坑十二，事务管理器绑错数据源，异常抛了、事务回滚了、数据还在。** Spring 去运营库拿连接、开事务、绑线程；`insert` 走交易库自己的连接，自动提交，返回前就落库了；异常触发的是运营库那个空事务的回滚。**不报错、不打 WARN、日志上还写着「事务回滚成功」。** 只有把调用前后的行数摆出来比才能发现，所以那个接口把 `ordersBefore` 和 `ordersAfter` 一起返回。残留数据那条 id 是 5 不是 4，自增主键不会因为回滚而退回去，别拿自增空洞当「丢没丢数据」的依据。

**坑十三，异常被中间层重新包装，`@ExceptionHandler` 接不住。** 这条是被一个失败的测试带出来的：按类型接 `DataSourceRoutingException` 接不到，路由失败发生在 mapper 取连接时，`SqlSessionTemplate` 把异常交给 `MyBatisExceptionTranslator` 翻成 `MyBatisSystemException`，原始异常进了 cause 链，请求落到通用兜底，那句最关键的 `Cannot determine target DataSource for lookup key [REPORT]` 被吃掉，**而且状态码还是 500，从响应上完全看不出有问题**，只断言状态码的测试会把这个 bug 放过去。修法是沿 cause 链找：

```java
private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
        if (type.isInstance(current)) {
            return type.cast(current);
        }
        if (current.getCause() == current) {
            break;
        }
    }
    return null;
}
```

在 `@ExceptionHandler(DataAccessException.class)` 里先 `findCause(ex, DataSourceRoutingException.class)`，找到再走专用处理。我没用 `getMostSpecificCause()`：它一路走到链尾，而这个 case 的链尾正好是最不需要的 `IllegalStateException`。**更通用的教训：按异常类型写 `@ExceptionHandler` 之前，先确认异常穿过多层框架之后类型还在不在。** 修复前后的差别有测试盯着，`ExceptionHandlerTest` 断言的是 `type` 和 `detail`，不只是状态码。

**坑十四，`spring.datasource.connection-fetch` 不是用来解决时序问题的。** `lazy` 只作用于自动配置出来的那个 `DataSource`，手工声明数据源配了也不生效，而且它是数据源级开关，不能按请求切换。管用的还是顺序，把 `@DataSource` 标在事务外面。

**坑十五，跨库事务这块，`ChainedTransactionManager` 别再用了。** 它在 Boot 4 里的位置是 `spring-data-commons`（不在 `spring-tx` 里），字节码里有 4 处 `Deprecated` 标记，是 Spring Data 内部用的东西。跨库这件事的正确边界是：

| 需求 | 做法 |
|---|---|
| 单库事务 | 每个数据源一个 `JdbcTransactionManager`，`@Transactional("xxx")` 指明名字 |
| 跨库强一致 | JTA / XA。Boot 4 有 `XADataSourceAutoConfiguration` 和 `JtaTransactionManager`，但需要 XA 版数据源和事务协调器 |
| 跨库最终一致 | 消息队列 + 本地消息表，超出本篇范围 |

动态方案还有一条自己的边界：一个事务只能落一个库，因为事务开在路由数据源上，取连接时问一次当前标识就绑死了。想在一个事务里同时改两个库，这个方案做不到，得上 XA。

## Boot 4 多数据源事实清单

这个主题在 Boot 4 上的关键事实，类包位置都以 4.1.1 实测为准。

| 项目 | 当前事实 |
|---|---|
| JDBC 自动配置包名 | `org.springframework.boot.jdbc.autoconfigure.*` |
| 事务自动配置包名 | `org.springframework.boot.transaction.autoconfigure.*` |
| 建表脚本 | `spring.datasource.schema` / `.data` 是 error 级废弃，改 `spring.sql.init.*`；多数据源下要手工喂 |
| 连接池 | Boot 默认 HikariCP，`DruidDataSource` 仍可用但不再是默认 |
| AOP starter | `spring-boot-starter-aspectj` |
| Web starter | `spring-boot-starter-webmvc` |
| `MybatisSqlSessionFactoryBean` | 3.5.17 起在 `com.baomidou.mybatisplus.spring.` |
| 事务 advisor 顺序 | 没有对应配置属性，只能从切面这头压 `@Order` |
| 跨库链式事务 | `ChainedTransactionManager` 在 `spring-data-commons`，带 `Deprecated`，别用 |
| 连接获取模式 | `spring.datasource.connection-fetch`（`eager` / `lazy`） |

## 小结

这一篇的难点从头到尾都藏在「不报错的隐式行为」里：自动配置静默退让、注解静默失效、事务静默不生效、异常静默降级，四条路全都不制造噪音，只会让结果错。关系型那几篇的坑多数会留下异常或者错日志，这一篇这些连痕迹都没有，只能靠对比和断言，那四个反例接口和专门断言「bean 不存在」的测试类就是这么来的。

选型建议一句话：库的角色固定就用静态方案，业务代码里看不到「切库」，啰嗦但直白；库的选择依赖运行时状态才上动态路由，代价是 `@DataSource` 会渗进 service，记得把它标在事务外面。没解决的事也有：一个事务管理器身上还有哪些东西是配置不出来的，比如超时、只读、隔离级别，以及那个没暴露的 advisor 顺序，这篇没展开，等我踩够了坑再单独写。两套方案、25 个用例、可以单独重置的数据脚本都在 `springboot4-multisource` 工程里。

署名 `Xiong Neng`，许可 MIT。

## 参考链接

- [Spring Boot 4.1 How-to - 配置两个数据源](https://docs.spring.io/spring-boot/how-to/data-access.html#howto.data-access.configure-two-datasources)
- [Spring Framework 7.0 - AbstractRoutingDataSource Javadoc](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html)
- [Spring Boot 4.1 Reference - 数据源与连接池](https://docs.spring.io/spring-boot/4.1/reference/data/sql.html#data.sql.datasource)
- [HikariCP 官方仓库](https://github.com/brettwooldridge/HikariCP)
