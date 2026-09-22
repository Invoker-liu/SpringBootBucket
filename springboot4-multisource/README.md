# springboot4-multisource

Spring Boot 4 全家桶教程 · 第 5 篇配套工程：**一个进程里同时连两个 MySQL 库**。

第 2 篇（MyBatis-Plus）里那个订单工程，数据源是启动时自动配好的那一个。
这一篇把它拆开：交易库放订单，运营库放商品主数据，两个库在同一个应用里共存。
工程里放了两套做法供对照——**静态多数据源**和**动态路由数据源**——
一边讲清楚"自动配置为什么会全线退让、手工要补哪些 bean"，
一边给出真实项目里更常见的"运行时按注解切库"。

顺便回答一个很多教程没讲清的问题：**事务管理器绑错数据源时会发生什么**。
答案是异常照抛、事务照回滚，数据却留在库里。这个反例在工程里有对应的接口可以真的跑一遍。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 自动配置退让 | 两个 `DataSource` 一注册，`JdbcTemplate`、事务管理器、`schema.sql` 初始化、**整条 MyBatis-Plus 自动配置**全部静默失效 |
| 静态多数据源 | 一个库一套 bean：`DataSource` + `SqlSessionFactory` + `MapperScannerConfigurer` + `DataSourceTransactionManager` |
| mapper 分包 | 三个 mapper 包分别绑三个 `SqlSessionFactory`，用 `@MapperScan(sqlSessionFactoryRef = ...)` 指定 |
| 路由数据源 | `AbstractRoutingDataSource` 子类 + `@DataSource` 注解 + AOP + `ThreadLocal` 栈 |
| 切面顺序 | 数据源切面必须排在事务切面**外面**，否则"注解标了却不生效" |
| 换线程的坑 | `ThreadLocal` 不过线程边界，`lenientFallback=false` 也拦不住 `null` 标识 |
| 自调用的坑 | 同类内部 `this.方法()` 绕过代理，`@DataSource` 被忽略（和 `@Transactional` 一个毛病） |
| 事务管理器绑错 | `bizTransactionManager` 配 `pos` 库的写入：异常抛了、事务回滚了、数据还在 |
| 异常被包装 | 路由失败穿过 MyBatis 之后类型变了，`@ExceptionHandler` 要沿 cause 链找才接得住 |
| 健康检查 | `/actuator/health` 会把路由数据源里每个 target 分别展开，是"两个库都连上了"最省事的证明 |
| 建表脚本 | `spring.datasource.schema` 已废，改用 `DataSourceInitializer` 给每个库单独喂脚本 |
| 对照实验 | 同样两个数据源，只要其中一个标 `@Primary`，上面那串自动配置就全活过来 |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9、Tomcat 11.0.24、Jackson 3.1.5、HikariCP 7.0.2） |
| MyBatis-Plus | 3.5.17（`mybatis-spring` 4.0.0、`mybatis` 3.5.19） |
| MySQL | 8.0+（本工程在 8.4 上验证） |

需要**两个**可连的 MySQL 库。工程里两个库可以放在同一个 MySQL 实例上，
这一点不影响演示——多数据源解决的是"应用要连两个地方的库"，
至于这两处在不在同一台机器上是另一件事。

## 三、准备数据库

脚本在 `src/main/resources/sql/` 下，一共五个：

| 文件 | 作用 |
|---|---|
| `db-init.sql` | 建两个空库：`springboot4_pos`、`springboot4_biz` |
| `pos-schema.sql` | 交易库建表：`t_user`、`t_order`、`t_order_item` |
| `pos-data.sql` | 交易库种子数据 |
| `biz-schema.sql` | 运营库建表：`t_user`、`t_product` |
| `biz-data.sql` | 运营库种子数据 |

```bash
mysql -uroot -p < src/main/resources/sql/db-init.sql
mysql -uroot -p springboot4_pos < src/main/resources/sql/pos-schema.sql
mysql -uroot -p springboot4_pos < src/main/resources/sql/pos-data.sql
mysql -uroot -p springboot4_biz < src/main/resources/sql/biz-schema.sql
mysql -uroot -p springboot4_biz < src/main/resources/sql/biz-data.sql
```

不过**其实不用手工执行**：应用启动时会自己跑一遍，见
`PosDataSourceConfig#posDataSourceInitializer`。
脚本留着是为了让你能单独重置数据，也为了让"建表这件事到底是谁干的"有个明确的归属。

### 两个库都有 t_user，这是故意的

两个库各有一张结构**完全一样**的 `t_user`，id 也一样，只有数据不同：

| 库 | id | username | real_name |
|---|---|---|---|
| springboot4_pos | 1 | `admin` | 交易库管理员 |
| springboot4_biz | 1 | `admin1` | 运营库管理员 |

查 `id=1` 拿到的是 `admin` 还是 `admin1`，一眼就能看出数据源切没切对。

为什么不用"不同的表名"来区分？因为切错了只会得到一个"表不存在"，
分不清是路由配错了还是建表脚本没跑。同名表 + 不同数据，把问题压缩成一个字段值的比对。

更进一步，探针 SQL 里还带了 `DATABASE()` 和 `CONNECTION_ID()`：

```sql
SELECT id, username, real_name,
       DATABASE()      AS databaseName,
       CONNECTION_ID() AS connectionId
FROM t_user WHERE id = 1
```

这两个是**连接**的属性，不是数据的属性。哪怕有人把两个库的数据改成一模一样，
这两个值照样骗不了人。正文里所有"切没切对"的判断都基于 `DATABASE()` 而不是 `username`。

## 四、运行

```bash
# 方式一：Maven 直接运行
./mvnw spring-boot:run

# 方式二：打包后运行
./mvnw clean package -DskipTests
java -jar target/springboot4-multisource-1.0.0.jar
```

默认监听 8080。数据库地址在 `application.yml` 的 `app.datasource.*` 下，
可以用环境变量覆盖：

```bash
DB_HOST=192.168.1.97 DB_USER=root DB_PASSWORD=root123456 java -jar target/springboot4-multisource-1.0.0.jar
```

注意 `application.yml` 里**没有** `spring.datasource.url`。不是漏了，是配了也没用——
见第七节第 1 条。

## 五、接口清单

### 方案 A：静态多数据源（`/api/static`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/static/snapshot` | 一次请求读两个库，各自报出自己的库名 |
| GET | `/api/static/orders` | 交易库：订单列表 |
| GET | `/api/static/orders/{id}` | 交易库：订单 + 明细（join 两张表），带 `databaseName` |
| GET | `/api/static/products?maxStock=50` | 运营库：按库存筛选商品 |
| GET | `/api/static/products/{id}` | 运营库：单个商品 |
| POST | `/api/static/orders/transaction-demo?orderNo=x&manager=pos\|biz` | 事务管理器对照实验 |

### 方案 B：动态路由数据源（`/api/dynamic`）

| 方法 | 路径 | 期望结果 |
|---|---|---|
| GET | `/api/dynamic/probe/pos` | 落到交易库 |
| GET | `/api/dynamic/probe/biz` | 落到运营库 |
| GET | `/api/dynamic/probe/both` | 一次请求里先后切两个库 |
| GET | `/api/dynamic/probe/unregistered` | **500**，路由失败 |
| GET | `/api/dynamic/probe/self-invocation` | **反例**：第 2 步切不过去 |
| GET | `/api/dynamic/probe/cross-bean` | 正例：切得过去，而且外层不受影响 |
| GET | `/api/dynamic/probe/switch-inside-transaction` | **反例**：事务里切不动 |
| GET | `/api/dynamic/probe/switch-before-transaction` | 正例：注解标在事务外面 |
| GET | `/api/dynamic/probe/another-thread` | **反例**：新线程落回默认库 |
| GET | `/actuator/health` | 路由数据源的每个 target 分别展开 |

## 六、两套方案对照

| | 方案 A：静态 | 方案 B：动态路由 |
|---|---|---|
| 额外依赖 | 无 | 无 |
| `DataSource` bean | 每个库一个 | 一个路由数据源，内部持有多个 target |
| `SqlSessionFactory` | 每个库一个 | **一个** |
| mapper 分包 | 必须，接口按库分开放 | 不需要，一套 mapper 走遍所有库 |
| 数据源选择发生在 | 依赖注入时 | 运行时，按注解 |
| 能不能改 | 改不了 | 加个注解就行 |
| 事务边界 | 每个库一个事务管理器，显式指定 | 一个事务管理器，一个事务只落一个库 |
| 适合什么 | 库的角色固定，读写分离里的从库、报表库 | 分库、按业务切库、多租户 |

静态方案为什么 bean 数量翻倍：一个 `SqlSessionFactory` =
「数据源 + MyBatis 配置 + 映射文件」的整包，库不同这个整包就得新建一套。

动态方案为什么 bean 拓扑和单数据源差不多：
MyBatis 眼里的"那个数据源"始终只有路由数据源一个，
库的切换发生在更下面一层。至于多出来的那一个数据源 bean 会不会让
自动配置又一次退让——会，因为容器里现在有三个 `DataSource`。
第七节第 8 条讲怎么把这一层也省掉。

## 七、踩过的坑

### 1. `spring.datasource.*` 里有一族属性已经不能写了

`spring-boot-jdbc` 的配置元数据里，这 7 个标着 `level: error`：

```
spring.datasource.schema          → spring.sql.init.schema-locations
spring.datasource.data            → spring.sql.init.data-locations
spring.datasource.initialization-mode → spring.sql.init.mode
spring.datasource.platform        → spring.sql.init.platform
spring.datasource.separator       → spring.sql.init.separator
spring.datasource.sql-script-encoding → spring.sql.init.encoding
spring.datasource.continue-on-error → spring.sql.init.continue-on-error
```

`level: error` 不是"打个 WARN 还能用"，是绑定时直接抛异常、**启动即失败**。
旧版多数据源教程清一色用 `spring.datasource.schema` 给两个库建表，
照抄过来第一步就过不去。

而且就算改成 `spring.sql.init.*` 也还是不行——那个自动配置挂在
`DataSourceInitializationAutoConfiguration` 上，它同样有
`@ConditionalOnSingleCandidate(DataSource)`，也没法告诉 Spring"这段脚本发给哪个库"。
所以本工程用的是 `DataSourceInitializer`，每个库一个：

```java
@Bean
public DataSourceInitializer posDataSourceInitializer(
        @Qualifier("posDataSource") DataSource posDataSource) {
    DataSourceInitializer initializer = new DataSourceInitializer();
    initializer.setDataSource(posDataSource);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
            new ClassPathResource("sql/pos-schema.sql"),
            new ClassPathResource("sql/pos-data.sql")));
    return initializer;
}
```

### 2. `@ConditionalOnSingleCandidate` 是这个主题的总开关

注册第二个 `DataSource` 之后，下面这些 bean 全都不见了：

| 自动配置 | 退让条件 | 后果 |
|---|---|---|
| `DataSourceAutoConfiguration` | `@ConditionalOnMissingBean(DataSource, XADataSource)` | 默认数据源不建（预期内） |
| `JdbcTemplateAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | `JdbcTemplate`、`NamedParameterJdbcTemplate` 没了 |
| `JdbcClientAutoConfiguration` | `@ConditionalOnSingleCandidate(NamedParameterJdbcTemplate)` | `JdbcClient` 跟着没了 |
| `DataSourceTransactionManagerAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | 没有 `PlatformTransactionManager` |
| `DataSourceInitializationAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | `schema.sql`/`data.sql` 不执行 |
| `MybatisPlusAutoConfiguration` | `@ConditionalOnSingleCandidate(DataSource)` | **mapper 扫描整体失效** |
| `TransactionAutoConfiguration$TransactionTemplateConfiguration` | `@ConditionalOnSingleCandidate(PlatformTransactionManager)` | `TransactionTemplate` 没了 |

退让是**静默**的：不报错、不警告，只是 bean 没了。
其中最坑的是 MyBatis-Plus 那条——报错信息会指向"找不到 XxxMapper"，
很容易往 mapper 包名、编译输出目录那个方向查，其实根因在数据源这边。

工程里有一组测试把这些"不存在"逐个断言下来了，
见 `AutoConfigurationBackoffTest`。

### 3. `@MapperScan` 一次只能声明一组规则

`@MapperScan` 是类级注解，一个类只能写一组。
所以两个库各占一个配置类，一个类里既声明数据源、又声明工厂、又声明扫描规则：

```java
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.xncoding.multisource.mapper.pos",
        sqlSessionFactoryRef = "posSqlSessionFactory")
public class PosDataSourceConfig { ... }
```

`sqlSessionFactoryRef` 指向的是**工厂生产出来的**那个 `SqlSessionFactory`，
不是 `MybatisSqlSessionFactoryBean` 本身。因为后者实现了 `FactoryBean`，
容器里以方法名注册的 bean 就是它 `getObject()` 的产物。

### 4. `MybatisSqlSessionFactoryBean` 的包名在 3.5.17 换过

```java
// 3.5.14 及以前
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
// 3.5.17
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
```

网上关于"多数据源 + MyBatis-Plus"的文章几乎都还写着旧包名，
照抄过来第一件事就是编译不过。这个类是用来替代 MyBatis 原生
`SqlSessionFactoryBean` 的，`setGlobalConfig()` 不能省——
`BaseMapper` 那套 CRUD 是靠 `GlobalConfig` 里的 `SqlInjector`
在解析 mapper 接口时注入进去的，不设就只有空接口，
表现是"找不到 statement"而不是编译错误。

### 5. 切面顺序：数据源切换必须排在事务外面

Spring 的事务拿到连接之后会把它绑在当前线程上。
所以"挑哪个库"必须在**取连接之前**做完。顺序反了会这样：

1. 事务先开，连接从默认库借出来并绑定；
2. 切面再改标识；
3. 之后的 SQL 走的还是那条已经绑好的连接——**注解看起来生效了，实际没生效**。

Advice 是按 order 从小到大往外包的，order 小在外面、先执行。
Boot 4 里 `@EnableTransactionManagement` 的 advisor 用的是默认值
`Ordered.LOWEST_PRECEDENCE`，而 `TransactionAutoConfiguration`
没有暴露任何调整它的属性，所以只能从切面这头压下去：

```java
@Aspect
@Component
@Order(DataSourceAspect.ORDER)   // = 0
public class DataSourceAspect { ... }
```

写 `0` 而不是 `Ordered.HIGHEST_PRECEDENCE`，是给别的切面留位置。

这件事有测试固定住，见 `AdvisorOrderTest`：它从代理对象上取出 advisor 链，
断言数据源切面的 order 严格小于事务 advisor 的 order，**而且**断言它在链上排得更靠外。
两个断言都写，是因为只比 order 值的话，哪天有人改了排序方式就漏了。

### 6. `@DataSource` 和 `@Transactional` 一样，自调用不生效

```java
@DataSource(DataSourceKey.BIZ)
public ProbeScenarioResult selfInvocation() {
    ...
    steps.add(probePos());   // ← this.调用，不走代理，@DataSource(POS) 被忽略
    ...
}
```

第 2 步标的是 `POS`，实际还是落在 `BIZ` 上。
`@Transactional`、`@Cacheable`、`@Async` 全是同一个毛病。
修法是把方法挪到另一个 bean 去（工程里是 `ProbeInnerService`），
或者用 `AopContext.currentProxy()`，后者需要在
`@EnableAspectJAutoProxy(exposeProxy = true)` 打开才行。

接口 `GET /api/dynamic/probe/self-invocation` 和 `/probe/cross-bean`
就是这一对正反例。

### 7. `lenientFallback=false` 挡不住 `null` 标识

`DynamicDataSource` 关了宽容回退，指望"key 找不到就报错，别静默跑到主库"。
但父类的源码是这么写的：

```java
DataSource dataSource = this.resolvedDataSources.get(lookupKey);
if (dataSource == null && (this.lenientFallback || lookupKey == null)) {
    dataSource = this.resolvedDefaultDataSource;
}
if (dataSource == null) {
    throw new IllegalStateException("Cannot determine target DataSource for lookup key [" + lookupKey + "]");
}
```

后半截 `lookupKey == null` 是**独立的一条路**：
标识为 `null` 时无条件回退到默认数据源，跟开关没关系。

于是换线程就成了事故现场——`ThreadLocal` 不过线程边界，
新线程上 `peek()` 返回 `null`，查询静默落到默认库，
接口返回 200，从响应上完全看不出问题。

`GET /api/dynamic/probe/another-thread` 就是这条路径。
它证明了：**想在新线程里用对库，只能把标识显式传进去，别指望上下文自己过去。**

这个结论和常见认知是相反的。很多文章说"设 `lenientFallback(false)` 就安全了"，
但那个开关只管"非 null 但没注册"的情况。两种情况的区别值得记一下。

### 8. 一个 `@Primary` 能让整串自动配置复活

既然退让条件是 `@ConditionalOnSingleCandidate`，那给其中一个数据源
标 `@Primary` 不就行了？——**行**。条件是"只有一个候选，或者有多个但恰好一个标了 `@Primary`"。

对动态方案的启发：如果项目**只走动态方案**，把 pos / biz 藏成路由数据源的内部目标、
不暴露成 bean，再给路由数据源标上 `@Primary`，
容器里就只剩一个候选，MyBatis-Plus 的自动配置会重新生效，
上面的 `SqlSessionFactory` 声明可以整段删掉。

`PrimaryAutoConfigurationProbeTest` 就是这件事的对照实验：
它单独起一个只声明一个 `@Primary` 数据源的上下文，
断言 `JdbcTemplate`、`JdbcClient`、`PlatformTransactionManager`、
`SqlSessionFactory`、`TransactionTemplate` 全部存在——
而 `AutoConfigurationBackoffTest` 在本工程的上下文里断言它们全部不存在。

本工程两套方案并存，为了各自独立，选了"全部手工、谁都不标 `@Primary`"这条更啰嗦但更直白的路。

### 9. 三个事务管理器，`@Transactional` 不写名字就报错

容器里有 `posTransactionManager`、`bizTransactionManager`、
`dynamicTransactionManager`，谁都没标 `@Primary`。
写裸的 `@Transactional` 会直接抛 `NoUniqueBeanDefinitionException`。

这是故意的。多数据源下"这个事务管的是哪个库"本来就该是一个被显式回答的问题，
逼你写出来是好事：

```java
@Transactional("posTransactionManager")
public void insertThenFailCorrectly(String orderNo) { ... }
```

### 10. 事务管理器绑错数据源：异常抛了、事务回滚了、数据还在

这是本工程里最值得单独拿出来讲的一条。看这段代码：

```java
@Transactional("bizTransactionManager")     // ← 运营库的事务管理器
public void insertThenFailWithWrongManager(String orderNo) {
    orderMapper.insert(newOrder(orderNo));  // ← 写的是交易库
    throw new IllegalStateException("故意失败");
}
```

会发生两件事：

1. `@Transactional("bizTransactionManager")` 让 Spring 去**运营库**拿了一条连接、
   开了一个事务，绑在当前线程上；
2. `orderMapper.insert(...)` 用的是**交易库**自己的连接，而那条连接不在任何事务里
   （Hikari 默认自动提交），所以 insert 在方法返回前就已经落库了。

紧接着抛异常，Spring 老老实实把运营库上那个事务回滚了——但那个事务里本来就没有写入。

跑一遍：

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

换成 `manager=pos`，同样的代码、同样的异常，`ordersAfter` 就回到 3 了。

**这个 bug 不报错、不打 WARN、日志上还显示"事务回滚成功"。**
要发现它，只能像上面这样把"调用前后的行数"摆出来比。

所以接口把 `ordersBefore` / `ordersAfter` 一起返回——让"回滚了但数据还在"
变成两个可以直接相减的数字，而不是一句需要相信的断言。

顺便一个副产品细节：查库会看到那条残留数据的 id 是 **5** 而不是 4。
因为正确的那次演示也 `INSERT` 过一次、拿到了 `id=4`，随后被回滚了——
而 MySQL 的 `AUTO_INCREMENT` 不会因为回滚而退回去。
所以自增主键出现空洞是正常的，别拿它当"有没有丢数据"的依据。

### 11. 异常被中间层重新包装，`@ExceptionHandler` 接不住

标了一个没注册的库，`DynamicDataSource` 会抛 `DataSourceRoutingException`。
于是很自然地写一个按类型的处理器：

```java
@ExceptionHandler(DataSourceRoutingException.class)
```

**接不到。** 路由失败发生在 mapper 取连接的时候，MyBatis 的
`SqlSessionTemplate` 会把底层异常交给 `MyBatisExceptionTranslator` 翻一遍，
翻出来的是 `MyBatisSystemException`，原始的 `DataSourceRoutingException`
被塞进了 cause 链。

结果就是请求落到通用兜底，返回一句"服务端内部错误"，
那句最关键的 `Cannot determine target DataSource for lookup key [REPORT]`
被吃掉了，排查时只能去翻日志。而且**状态码还是 500，从响应上看不出来有问题**——
只断言状态码的测试会把这个 bug 放过去。

修法是沿 cause 链找：

```java
@ExceptionHandler(DataAccessException.class)
public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex) {
    DataSourceRoutingException routing = findCause(ex, DataSourceRoutingException.class);
    if (routing != null) {
        return handleDataSourceRouting(routing);
    }
    ...
}
```

`findCause` 没用 `NestedRuntimeException#getMostSpecificCause()`——
那个一路走到链尾，而要找的往往在中间：
`MyBatisSystemException → DataSourceRoutingException → IllegalStateException`，
走到链尾拿到的是 `IllegalStateException`，正好是最不需要的那个。

更通用的教训：**按异常类型写 `@ExceptionHandler` 之前，
先确认异常穿过多层框架之后类型还在不在。**
中间件重新包装异常是很常见的做法。

修复前后的差别有测试盯着，见 `ExceptionHandlerTest`——
它断言的是 `type` 和 `detail`，不只是状态码。

### 12. 健康检查是"两个库都连上了"最省事的证明

加 `spring-boot-starter-actuator` 之后：

```bash
curl http://127.0.0.1:8080/actuator/health
```

```json
{
  "components": {
    "db": {
      "components": {
        "bizDataSource": { "details": { "database": "MySQL", "validationQuery": "isValid()" }, "status": "UP" },
        "dynamicDataSource": {
          "components": {
            "BIZ": { "details": { "database": "MySQL", "validationQuery": "isValid()" }, "status": "UP" },
            "POS": { "details": { "database": "MySQL", "validationQuery": "isValid()" }, "status": "UP" }
          },
          "status": "UP"
        },
        "posDataSource": { "details": { "database": "MySQL", "validationQuery": "isValid()" }, "status": "UP" }
      },
      "status": "UP"
    }
  }
}
```

注意 `dynamicDataSource` 下面自己展开了 `BIZ` 和 `POS` 两个条目。

这不是巧合：`spring-boot-jdbc` 里有一个专门为 `AbstractRoutingDataSource`
写的 `RoutingDataSourceHealthContributor`，而且
`DataSourceHealthContributorAutoConfiguration` 的 `@ConditionalOnClass`
里显式列了 `org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource`。

也就是说，**路由数据源是 Spring Boot 官方认可的一等用法**，不是社区 hack。
这一点值得单独强调，因为 `ChainedTransactionManager`（跨库链式事务那套）
在 Boot 4 里的处境完全相反——它被挪进了 `spring-data-commons`，
而且带 4 处 `Deprecated` 标记。

### 13. 别用 `spring.datasource.connection-fetch` 解决时序问题

Boot 4 新加了这个属性，取值 `eager`（默认）/ `lazy`：

| 值 | 含义 |
|---|---|
| `eager` | 直连，立刻取连接 |
| `lazy` | 尽可能晚取，没有语句执行就完全不取 |

看到 `lazy` 很容易想"那我把连接取晚一点，事务里切换不就也能生效了？"

它作用于**自动配置出来的那个** `DataSource`。本工程手工声明数据源，配了也不生效。
而且它是数据源级的开关，不能按请求切换。

真正管用的还是顺序：把 `@DataSource` 标在事务外面。

## 八、运行测试

```bash
./mvnw test
```

共 25 个用例，全部连真实的两个 MySQL 库：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `DynamicRoutingTest` | 9 | 路由切换、以及四个"应该失败"的反例 |
| `StaticDataSourceTest` | 6 | 静态绑定、join、事务管理器正反例 |
| `AutoConfigurationBackoffTest` | 5 | 断言一串自动配置的 bean **不存在** |
| `AdvisorOrderTest` | 2 | advisor 链上数据源切面排在事务切面之前 |
| `ExceptionHandlerTest` | 2 | 被包装过的路由失败仍然返回带明细的 problem+json |
| `PrimaryAutoConfigurationProbeTest` | 1 | 对照实验：一个 `@Primary` 让自动配置复活 |

`AutoConfigurationBackoffTest` 那一组用例全部是"某个 bean 不存在"的断言，
看起来有点怪，但它们正好对应你会遇到的那几个异常：

- `NoSuchBeanDefinitionException: JdbcTemplate`
- `NoSuchBeanDefinitionException: PlatformTransactionManager`
- `NoSuchBeanDefinitionException: XxxMapper`（报错方向最坑的一个）

`StaticDataSourceTest` 会在 `@AfterEach` 里把自己造的数据删掉
（订单号统一用 `TEST-MS-` 前缀），不污染演示数据。

跑之前建议先把两个库重置一遍：

```bash
mysql -uroot -p < src/main/resources/sql/db-init.sql
# ... 然后重新执行四个 schema/data 脚本
```

应用启动时本来就会重跑一遍初始化脚本，所以正常情况下数据是干净的；
手工重置只是为了让"脚本单独执行"这条路也能走通。

## 九、工程结构

```
springboot4-multisource/
├── pom.xml
├── src/main/java/com/xncoding/multisource/
│   ├── MultisourceApplication.java
│   ├── annotation/DataSource.java            注解：声明走哪个库
│   ├── context/
│   │   ├── DataSourceKey.java                逻辑库枚举（含"映射到哪个物理库"）
│   │   └── DataSourceContextHolder.java      ThreadLocal 栈
│   ├── routing/
│   │   ├── DynamicDataSource.java            AbstractRoutingDataSource 子类
│   │   └── DataSourceAspect.java             @Order(0)，必须在事务之前
│   ├── config/
│   │   ├── MybatisFactorySupport.java        三个工厂共用的构造逻辑
│   │   ├── PosDataSourceConfig.java          方案 A：交易库
│   │   ├── BizDataSourceConfig.java          方案 A：运营库
│   │   └── DynamicDataSourceConfig.java      方案 B：路由数据源
│   ├── domain/                               Order / OrderItem / Product / ProbeUser
│   ├── mapper/pos/OrderMapper.java
│   ├── mapper/biz/ProductMapper.java
│   ├── mapper/probe/ProbeUserMapper.java     探针，绑路由数据源
│   ├── service/
│   │   ├── PosOrderService.java              方案 A
│   │   ├── BizProductService.java            方案 A
│   │   ├── ProbeService.java                 方案 B（正反例都在这里）
│   │   ├── ProbeInnerService.java            为了让跨 bean 调用成立而拆出来的
│   │   └── ProbeQueries.java
│   ├── controller/
│   │   ├── StaticDataSourceController.java
│   │   └── DynamicDataSourceController.java
│   ├── dto/                                  DataSourceProbeResult / ProbeScenarioResult / ...
│   └── exception/                            GlobalExceptionHandler 等
├── src/main/resources/
│   ├── application.yml
│   ├── mapper/{pos,biz,probe}/*.xml           每个工厂只加载自己那一份
│   └── sql/                                   5 个脚本
└── src/test/java/com/xncoding/multisource/    6 个测试类
```

## 十、从 Spring Boot 2.0 迁过来要注意什么

| 项 | 旧写法 | Boot 4 |
|---|---|---|
| 连接池 | Druid | HikariCP（Boot 默认），`DruidDataSource` 仍可用但不再是默认 |
| 建表脚本 | `spring.datasource.schema` / `.data` | **error 级废弃**，改用 `spring.sql.init.*`；多数据源下要手工喂 |
| 自动配置包名 | `org.springframework.boot.autoconfigure.jdbc.*` | `org.springframework.boot.jdbc.autoconfigure.*` |
| 事务自动配置包名 | `org.springframework.boot.autoconfigure.transaction.*` | `org.springframework.boot.transaction.autoconfigure.*` |
| `AopAutoConfiguration` | `spring-boot-starter-aop` | 改叫 `spring-boot-starter-aspectj` |
| `MybatisSqlSessionFactoryBean` | `...extension.spring.` 包 | 3.5.17 起在 `com.baomidou.mybatisplus.spring.` |
| 事务器顺序 | `@EnableTransactionManagement(order = 2)` | 没有这个属性了，只能从切面这头压 `@Order` |
| 跨库链式事务 | `ChainedTransactionManager` | 挪到了 `spring-data-commons`，且带 `Deprecated`，别再用了 |
| 连接获取模式 | 无 | 新加 `spring.datasource.connection-fetch`（`eager`/`lazy`） |

关于跨库事务，工程没给方案，这里说清楚边界：

- **单库事务** → 每个数据源一个 `DataSourceTransactionManager`，
  `@Transactional("xxxTransactionManager")` 指明用哪个；
- **跨库强一致** → 属于 JTA / XA 的范畴。Boot 4 里有
  `XADataSourceAutoConfiguration` 和 `JtaTransactionManager`，
  但需要 XA 版数据源和事务协调器，是另一个量级的话题；
- **跨库最终一致** → 消息队列 / 本地消息表，超出本篇范围。

`ChainedTransactionManager` 那条路现在既不在 `spring-tx` 里、也已经废弃，
不建议作为新项目的选型依据。

## 十一、许可

MIT。署名 `Xiong Neng`。
