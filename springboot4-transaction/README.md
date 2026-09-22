# springboot4-transaction

Spring Boot 4 全家桶教程 · 第 6 篇配套工程：**声明式事务的边界在哪里**。

事务注解是 Spring 里最"看起来不用学"的一个东西 —— 加个 `@Transactional` 就完事了。
但这个工程存在的理由恰恰是：它**不生效的时候不会报错**。异常照抛、接口照返回、
日志干干净净，只有两个地方看得出问题：数据库里的数据，和事务的 DEBUG 日志。
而这两样平时都没人看。

所以这一篇不写业务，只做实验。工程里准备了 40 多个接口，每个接口对应一个明确的问题，
跑完看余额就知道答案。所有结论都配了可运行的断言（46 个测试用例）。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 回滚规则 | 默认只回滚 `RuntimeException` 和 `Error` —— **受检异常抛出去，事务照常提交** |
| 纠错 | `rollbackFor` / `noRollbackFor` 的默认值是**空数组**，不是"Throwable 的子类" |
| 传播行为 | 七个级别全配了接口，重点对照 `REQUIRED` / `REQUIRES_NEW` / `NESTED` 三者的失败路径 |
| 事务失效 | 五类场景：同类内部调用、方法可见性、异常被吞、换线程、事务管理器名字写错 |
| 只读事务 | `readOnly = true` 在这套技术栈上**真的拦得住写**，但拦在 JDBC 驱动的客户端 |
| 隔离级别 | `Isolation.DEFAULT` 的真实语义是"**什么都不设**"，不是"设成数据库默认值" |
| 编程式事务 | `TransactionTemplate`：能 `setRollbackOnly()` 而不抛异常，受检异常反而必然回滚 |
| 事务事件 | `@TransactionalEventListener` 四个相位的实测差异，以及 Framework 6.1 起的**启动期强制校验** |
| 探针取证 | 用 `TransactionSynchronizationManager` + `CONNECTION_ID()` + `hasSavepoint()` 读运行时状态 |
| 检查接口 | `/api/accounts/transaction-managers` 直接告诉你容器里有几个事务管理器、叫什么名字 |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9、HikariCP 7.0.2） |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.0+（本工程在 8.4 + Connector/J 9.7.0 上验证） |

只需要**一个**库。事务这个主题不需要多数据源，
`@Transactional` 不写名字时的"按类型找唯一实例"规则，在第 5 篇里已经用两个库讲过了。

**为什么不用 H2 跑**：本篇一半的结论依赖具体数据库和驱动的行为 ——
`readOnly` 拦不拦得住写、隔离级别有没有落到连接上、`NESTED` 会不会真的建 savepoint、
子线程拿到的是不是另一条物理连接。换 H2 跑出来的结论不能代表 MySQL。

## 三、准备数据库

两个脚本，都在 `src/main/resources/sql/` 下：

| 文件 | 作用 |
|---|---|
| `schema.sql` | 建两张表：`t_account`（账户）、`t_tx_event`（事务事件） |
| `data.sql` | 四个账户的种子数据 |

```sql
-- 库
CREATE DATABASE IF NOT EXISTS springboot4_tx DEFAULT CHARACTER SET utf8mb4;

-- 表
CREATE TABLE IF NOT EXISTS t_account (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    owner      VARCHAR(32)    NOT NULL,
    balance    DECIMAL(12, 2) NOT NULL,
    version    INT            NOT NULL DEFAULT 0,
    updated_at DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner (owner)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 种子：四个账户，总额恒为 2000.00
INSERT INTO t_account (id, owner, balance, version, updated_at)
VALUES (1, '熊大', 1000.00, 0, NOW(3)),
       (2, '熊二',  500.00, 0, NOW(3)),
       (3, '熊三',  300.00, 0, NOW(3)),
       (4, '熊四',  200.00, 0, NOW(3));
```

不过**不用手工执行**：`spring.sql.init.mode=always`，应用每次启动都会跑一遍
`schema.sql` + `data.sql`，把余额刷回固定初值。脚本留着是为了让你能单独重置数据。

### 为什么是四个账户、两对操作

因为传播行为要演示"内外层"：

- **外层**统一操作 1 号（熊大）→ 2 号（熊二），金额 100.00
- **内层**统一操作 3 号（熊三）→ 4 号（熊四），金额 50.00

**分成两对账户不是随意的。** 如果内外层都动同一对账户，
`REQUIRES_NEW` 那条新连接会去等外层还没提交的行锁，实验最终以锁等待超时收场 ——
你看到的是 InnoDB 的锁行为，不是传播行为。

分成两对之后，读结果变得极其简单：

| 看谁 | 余额是初值 | 余额变了 |
|---|---|---|
| 熊大（1000.00） | 外层回滚了 | 外层提交了 |
| 熊三（300.00） | 内层回滚了 | 内层提交了 |

### 为什么读余额要放在事务外面

这是整篇最容易写错、也最容易骗自己的一点。

假设一个方法内部先转账、然后读余额来判断"提交了没有"——
读到的当然是转账后的值。**未提交的事务当然读得到自己写的值**，
这跟它最终会不会提交没有任何关系。

所以工程把读余额这件事单独抽出来了：

- 所有 `service` 层方法**只记录过程**，不返回余额；
- `ScenarioRunner#record` 在**业务调用返回、事务已经结束之后**才去读余额和总额；
- 四个账户的 `total` 每次都会一起返回。转账不改变总额，它是一道恒定成立的护栏 ——
  这个数字变了说明有笔转账只落了一半，比单独看某个账户更早发现问题。

## 四、运行

```bash
# 方式一：Maven 直接运行
.workbuddy/tools/mvn.sh spring-boot:run

# 方式二：打包后运行
.workbuddy/tools/mvn.sh -DskipTests package
java -jar target/springboot4-transaction-1.0.0.jar
```

默认端口 8080，数据库指向 `192.168.1.97:3306`，都可以用环境变量覆盖：

```bash
SERVER_PORT=18086 DB_HOST=127.0.0.1 DB_PASSWORD=xxx java -jar target/springboot4-transaction-1.0.0.jar
```

### 想看事务日志，必须把级别调到 DEBUG

`application.yml` 里已经把这几行打开了：

```yaml
logging:
  level:
    org.springframework.transaction: DEBUG
    org.springframework.transaction.interceptor: DEBUG
    org.springframework.jdbc.support.JdbcTransactionManager: DEBUG
```

**为什么必须手动开**：事务的每一步都有日志，但**一条都不在 INFO 级**。
默认配置下"事务到底开没开"是看不见的 —— 这正是它容易出问题却难发现的原因。

打开之后你能看到这些（都是原文）：

```
Creating new transaction with name [com.xncoding.transaction.service.RollbackService.plainCommit]
Participating in existing transaction
Suspending current transaction
Creating nested transaction with name [...]
Resuming suspended transaction after completion of inner transaction
Initiating transaction commit
Initiating transaction rollback
Transactional code has requested rollback
Participating transaction failed - marking existing transaction as rollback-only
Switching JDBC Connection [...]
Releasing JDBC Connection [...]
```

## 五、接口清单

### 账户（`/api/accounts`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/accounts` | 当前余额 + 总额 |
| POST | `/api/accounts/reset` | 刷回固定初值 |
| GET | `/api/accounts/transaction-managers` | 容器里有几个事务管理器、叫什么名字、什么类型 |

### 回滚规则（`POST /api/rollback/*`）

| 路径 | 预期结果 |
|---|---|
| `/plain` | 提交。熊大 900.00（对照组） |
| `/runtime` | 回滚，余额不动 |
| `/checked` | **提交**，熊大 900.00，异常照抛 |
| `/checked-rollback-for` | 回滚 |
| `/no-rollback-for` | 提交，异常照抛 |
| `/exception-class` | 回滚 |

### 传播行为（`POST /api/propagation/*`）

| 路径 | 熊大 | 熊三 | 说明 |
|---|---|---|---|
| `/required-inner-fails-caught` | 1000.00 | 300.00 | 抛 `UnexpectedRollbackException` |
| `/required-inner-fails-not-caught` | 1000.00 | 300.00 | 异常穿出 |
| `/required-inner-succeeds` | 900.00 | 250.00 | 对照组 |
| `/requires-new-inner-fails-caught` | 900.00 | 300.00 | 外层提交成功 |
| `/nested-inner-fails-caught` | 900.00 | 300.00 | 退回 savepoint |
| `/nested-inner-succeeds` | 900.00 | 250.00 | savepoint 白打 |
| `/not-supported-inner` | 1000.00 | **250.00** | 内层那笔留下了 |
| `/mandatory-without-transaction` | 1000.00 | 300.00 | `IllegalTransactionStateException` |
| `/never-inside-transaction` | 1000.00 | 300.00 | `IllegalTransactionStateException` |
| `/supports-outside-transaction` | 1000.00 | 250.00 | 不开事务 |
| `/supports-inside-transaction` | 900.00 | 250.00 | 加入外层 |

### 失效场景（`POST /api/failure/*`）

| 路径 | 熊大 | 结论 |
|---|---|---|
| `/self-invocation` | 900.00 | `this` 调用，失效 |
| `/self-invocation-fixed` | 1000.00 | 取代理再调，生效 |
| `/public-method` | 1000.00 | 生效 |
| `/protected-method` | 1000.00 | **生效** |
| `/package-private-method` | 1000.00 | **生效** |
| `/final-method` | — | 抛 **NPE**（见坑 5） |
| `/final-method-with-params` | 900.00 | 失效，钱留下 |
| `/static-method` | 900.00 | 失效 |
| `/swallowed` | 900.00 | 异常被吞，失效 |
| `/rethrow` | 1000.00 | 生效 |
| `/rethrow-as-checked` | 900.00 | 包成受检异常，**失效** |
| `/swallowed-but-marked` | 1000.00 | 生效（`setRollbackOnly`） |
| `/child-thread` | 1000.00（熊三 250.00） | 子线程那笔留下 |
| `/child-thread-with-own-transaction` | 1000.00（熊三 250.00） | 子线程独立提交 |
| `/manager-anonymous` | 900.00 | 按类型找唯一实例 |
| `/manager-named` | 900.00 | 按名字取 |
| `/manager-wrong-name` | 1000.00 | `NoSuchBeanDefinitionException` |

### 只读 / 隔离 / 编程式 / 事件

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/readonly/writable` | 对照：没打 `readOnly` |
| GET | `/api/readonly/read` | 只读事务读 |
| GET | `/api/readonly/write` | **只读事务里写入，被驱动拦下** |
| GET | `/api/isolation` | 四种隔离级别一次看全 |
| POST | `/api/programmatic/commit` | 编程式：正常提交 |
| POST | `/api/programmatic/rollback-by-flag` | `setRollbackOnly()`，HTTP 200 但回滚 |
| POST | `/api/programmatic/rollback-by-exception` | 抛异常回滚 |
| POST | `/api/programmatic/rollback-by-wrapped-checked` | 受检异常包装后抛 → 必然回滚 |
| POST | `/api/programmatic/anonymous-class` | 匿名内部类写法 |
| POST | `/api/programmatic/requires-new` | 用 REQUIRES_NEW 模板 |
| POST | `/api/events/commit` | 事件：事务提交（4 条） |
| POST | `/api/events/rollback` | 事件：事务回滚（`BEFORE_COMMIT` 消失） |
| POST | `/api/events/no-transaction` | 事件：没有事务（1 条） |
| GET | `/api/events` | 事件表当前内容 |

## 六、四层探针怎么读

每个场景返回的 `steps` 里都带一行事务状态。为了不靠猜，这个工程用了三个探针：

| 探针 | 读什么 | 能回答什么问题 |
|---|---|---|
| `TransactionSynchronizationManager` | 纯内存状态：有没有事务、事务名、只读标记、隔离级别 | 当前线程上到底绑没绑事务 |
| `CONNECTION_ID()` / `DATABASE()` | 真去数据库问当前连接 | 两笔操作在不在同一条物理连接上 |
| `TransactionAspectSupport.currentTransactionStatus()` | 事务内部结构 | 有没有 savepoint（NESTED 的唯一直接证据） |

一次典型的读数长这样：

```
事务=有, 事务名=com.xncoding.transaction.service.PropagationOuterService.requiredInnerSucceeds,
只读=false, 隔离级别=跟随数据库, 连接=8990, savepoint=false
```

对照 NESTED 的那次：

```
事务=有, 事务名=com.xncoding.transaction.service.PropagationOuterService.nestedInnerSucceeds,
只读=false, 隔离级别=跟随数据库, 连接=8990, savepoint=true
```

**`savepoint=false` → `savepoint=true`，就是 NESTED 真的生效了的全部证据。**
连接 ID 全程 8990 没变，说明它没用新连接 —— 这正是 NESTED 和 REQUIRES_NEW 的分野。

## 七、踩过的坑

### 1. `rollbackFor` 的默认值是空数组，不是"Throwable 的子类"

先把这个流传很广的错误说法清掉。`@Transactional` 的 `rollbackFor` /
`noRollbackFor` 默认值都是**空数组**。真正决定回不回滚的是另一处：

```java
// DefaultTransactionAttribute#rollbackOn
return (ex instanceof RuntimeException || ex instanceof Error);
```

也就是说：**受检异常抛出去，事务照样提交**。异常会正常传播给调用方，
所以调用方以为失败了，数据却已经落库。这是本篇最值钱的一个坑 ——
它的表现和"成功"几乎一样，只有查库才看得出来。

跑一下 `/api/rollback/checked` 就知道了：

```json
{
  "scenario": "默认规则 · 受检异常",
  "steps": [
    { "label": "调用抛出 TransferRejectedException",
      "detail": "message = 故意抛受检异常，看它回不回滚" }
  ],
  "accounts": [
    { "id": 1, "owner": "熊大", "balance": 900.00 },
    { "id": 2, "owner": "熊二", "balance": 600.00 }
  ],
  "total": 2000.00
}
```

异常抛了，钱也扣了。规矩可以定得很简单：**方法签名上只要出现 `throws`，
`rollbackFor` 就必须写。**

### 2. catch 之后包成受检异常再抛，等于白抛

`/api/failure/rethrow-as-checked` 演示的是比"忘了抛"更隐蔽的一种：

```java
try {
    throw new BalanceNotEnoughException("业务上失败了");
} catch (BalanceNotEnoughException e) {
    // 看起来"我明明抛出去了"
    throw new TransferRejectedException("包装后的受检异常", e);
}
```

异常确实抛出去了，调用方也收到了，HTTP 也返回了 422。
但类型从 `RuntimeException` 换成了受检异常，默认回滚规则就不认了 —— 钱留下了。

为了匹配方法签名而顺手做的一次类型转换，足以把回滚弄丢。
修法有两种：原样重新抛，或者吞掉异常但显式调
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`。

### 3. 内层失败被外层 catch 住，提交时抛 `UnexpectedRollbackException`

这个异常的成因和报错位置是**分开的**，所以特别难查。

```java
@Transactional
public void outer() {
    move(1L, 2L, 100.00);
    try {
        inner.requiredFails();          // 内层 REQUIRED，和外层是同一条事务
    } catch (BalanceNotEnoughException e) {
        // "我 catch 了，所以没事"
    }
    // 走到这里，提交时抛 UnexpectedRollbackException
}
```

内外层是同一条事务。内层抛异常时，事务被标成 rollback-only；
**这个标记不是异常，catch 不掉**。到提交的那一刻事务管理器才发现问题，
于是抛：

```
Transaction rolled back because it has been marked as rollback-only
```

DEBUG 日志里能提前看到征兆：

```
Participating transaction failed - marking existing transaction as rollback-only
Setting JDBC transaction [...] rollback-only
Global transaction is marked as rollback-only but transactional code requested commit
```

看到 `UnexpectedRollbackException`，就去内层找那个被吞掉的异常。

### 4. "非 public 方法事务失效"是个半对半错的结论

这句话流传极广，但它把两个独立的条件混成了一个。拆开看：

| 修饰符 | Spring 当事务方法 | CGLIB 能覆写 | 实际结果 |
|---|---|---|---|
| `public` | 是 | 是 | 生效 |
| `protected` | 是 | 是 | **生效** |
| 包私有 | 是 | 是 | **生效** |
| `public final` | 是 | 否 | 失效 |
| `static` | 是 | 否 | 失效 |
| `private` | 是 | 否 | 失效 |

关键在于 `AnnotationTransactionAttributeSource` 的 `publicMethodsOnly` 字段。
它的默认值确实是 `true`，但 `@EnableTransactionManagement` 走的**不是无参构造**：

```java
// AbstractTransactionManagementConfiguration#transactionAttributeSource
AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource(false);
```

`false` 就是 `publicMethodsOnly`。所以从 Spring 6.0 起，
**protected 和包私有方法一样会被事务拦截器考虑**，再加上 CGLIB 能覆写它们，
两个条件都满足，事务就生效了。

跑一下对比 `/api/failure/protected-method` 和 `/api/failure/package-private-method`：
两者的熊大余额都是 1000.00，也就是那笔转账真的回滚了。

> 唯一的前提是走了 CGLIB 代理。如果项目里把 `spring.aop.proxy-target-class`
> 设成 `false` 改用 JDK 动态代理，这些非 public 方法在代理对象上根本不存在，会一起失效。

### 5. `final` 方法上的事务失效，症状是 NPE 而不是数据不一致

这是这个工程里最意外的发现。

`/api/failure/final-method` 返回的不是"事务失效、钱留下"，而是：

```
java.lang.NullPointerException: Cannot invoke
  "com.xncoding.transaction.support.TxProbe.summary()" because "this.probe" is null
```

原因链有三步：

1. CGLIB 覆写不了 `final` 方法，代理类里没有它的覆盖版本；
2. 于是调用落在**代理实例自己**身上，而不是被转发给目标对象；
3. Spring 用 **Objenesis** 创建 CGLIB 代理实例，**不走目标类的构造器** ——
   所以代理实例自己的实例字段全是默认值，也就是 `null`。

正常调用时完全看不出来，因为代理会把请求转给真正的目标对象（字段都在它身上）。
只有 `final` 这种"绕过转发"的路径才会撞上。

排错时这条极其容易把人带偏：报的是 NPE，看起来像依赖注入没生效，
根因却是"方法被声明成了 final"。

`/api/failure/final-method-with-params` 是同一个方法的另一半：
把依赖从实例字段换成方法参数，NPE 消失，露出真实结果 —— 熊大 900.00，事务没生效。

### 6. 同类内部调用：`this` 不是代理

所有失效场景里频率最高的一个，原因却很简单：事务靠**代理**实现，
而 `this.xxx()` 里的 `this` 是原始对象。

判断标准可以缩成一句话：**这次调用有没有经过那个被注入到别处的对象。**

工程里给了修复版的接口，`detail` 里会打印实际类型：

```
实际类型 = com.xncoding.transaction.failure.SelfInvocationService$$SpringCGLIB$$0
```

留意 `$$SpringCGLIB$$` 这个后缀 —— 它也是"Boot 默认用 CGLIB 代理"的直接证据。

### 7. 事务状态存在 ThreadLocal 里，跨不了线程

`/api/failure/child-thread` 里，主线程在事务里转出 100.00，
子线程又转出 50.00，然后主线程抛异常。

结果是：**主线程那 100.00 回滚了，子线程那 50.00 留在库里。**

子线程读到的事务状态是"无" —— 它从连接池另借一条连接、以自动提交的方式跑完。
换成虚拟线程、`@Async`、`CompletableFuture`、并行流，结果一模一样：
**绑的是线程，不是具体哪种线程。**

正确做法不是"想办法把事务传过去"（传不过去，也不该传），
而是**让子线程有自己的事务边界** —— 把 `TransactionTemplate` 传进子线程里执行。
但要说清楚：这修的是"子线程内部要么全成要么全败"，
**跨线程没有原子性可言**，主事务和子事务之间的一致性得靠补偿、对账这类业务手段处理。

### 8. `AFTER_COMMIT` + 默认 `@Transactional` 会让应用起不来（这是好事）

`@TransactionalEventListener` 的 `AFTER_COMMIT` 相位里要写库，必须配
`@Transactional(propagation = REQUIRES_NEW)`。不写传播行为的话，
原事务已经结束，REQUIRED 会当成"加入已有事务"处理，
SQL 执行了却没人在最后提交它 —— 程序不报错，数据凭空消失。

Spring Framework 6.1 起，这种写法从"静默丢数据"改成了"启动就报错"：
`@EnableTransactionManagement` 现在注册的是
`RestrictedTransactionalEventListenerFactory`，它会在启动阶段校验每一个
`@TransactionalEventListener` 方法。工程里 `TrapListener` 就是这个反面教材，
只在 `listener-trap` profile 下加载：

```bash
java -jar target/springboot4-transaction-1.0.0.jar \
    --server.port=18087 --spring.profiles.active=listener-trap
```

进程退出码 1，报错原文：

```
org.springframework.beans.factory.BeanInitializationException: Failed to process @EventListener
annotation on bean with name 'trapListener': @TransactionalEventListener method must not be
annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED:
public void com.xncoding.transaction.event.TrapListener.onAfterCommit(...AccountChangedEvent)
Caused by: java.lang.IllegalStateException: @TransactionalEventListener method must not be
annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED: ...
```

`LedgerListener`（正式的监听器）里的四条 `@Transactional` 是按规则写对的，
注释里逐条说明了原因。这个校验看着严格，其实是在救你。

### 9. `readOnly = true` 在这套技术栈上真的拦得住写，但拦在驱动层

先看探针读数 —— 两层都变了：

```
Spring 声明=true, 数据库会话=只读
```

写入被拒绝：

```
TransientDataAccessResourceException:
### Cause: java.sql.SQLException: Connection is read-only.
    Queries leading to data modification are not allowed
### SQL: UPDATE t_account SET balance = balance + ? ... WHERE id = ?
```

关键在于**这个错是谁报的**。追一下文案来源：
它在 `com.mysql.cj.LocalizedErrorMessages.properties` 里
（`Statement.27` + `Statement.28` 拼起来），由 `com.mysql.cj.jdbc.StatementImpl` 抛出。
**也就是说，拦截发生在 JDBC 驱动的客户端，不是 MySQL 服务端。**

`@@session.transaction_read_only` 读出来是 1，说明 Connector/J 的 `setReadOnly`
确实发出了 `SET SESSION TRANSACTION READ ONLY`。

结论：这条防线依赖"驱动 + 连接池"的实现，换一套就可能只剩语义。
但在这套技术栈上它是真拦的，而且异常是 `RuntimeException`，事务顺带回滚 ——
这一点和"受检异常不回滚"那条规则正好错开，别混起来记。

### 10. `Isolation.DEFAULT` 的真实语义是"什么都不设"

四种声明的实测读数：

| 声明 | Spring 侧读数 | 数据库会话 |
|---|---|---|
| `Isolation.DEFAULT` | `null` | `REPEATABLE-READ`（MySQL 默认） |
| `READ_COMMITTED` | `2` | `READ-COMMITTED` |
| `REPEATABLE_READ` | `4` | `REPEATABLE-READ` |
| `SERIALIZABLE` | `8` | `SERIALIZABLE` |

第一行和第二行看着"结果一样"，过程完全不同：`DEFAULT` 连
`SET TRANSACTION ISOLATION LEVEL` 这句都没发，数据库保持会话默认值。
换一个默认级别不是 REPEATABLE-READ 的数据库，两者的行为就分开了。

**还有一个前提条件**：隔离级别是在**事务开始、借到连接之后**才落上去的。
如果当前方法是加进外层已有事务的（传播行为不是 `REQUIRES_NEW` / `NESTED`），
连接早就借好了，这句 `SET` 不会再执行 —— 声明的隔离级别被**静默忽略**。
`readOnly` 同理。

### 11. 编程式事务有两个和声明式相反的结论

**第一，受检异常必然回滚。** 不是 Spring 改了规则，而是
`TransactionCallback` 的方法签名里没有 `throws Exception`，
受检异常根本传不出 lambda，你必须把它包成 `RuntimeException` 才抛得出去。
一旦包了就是运行时异常，默认规则自然判它回滚。

于是同一个受检异常出现了两种结果：

| 写法 | 结果 |
|---|---|
| 声明式 `@Transactional` + 抛受检异常 | **提交** |
| 编程式 `TransactionTemplate` + 抛受检异常 | **回滚**（因为必须先包装） |

**第二，编程式事务在日志里没有名字。** 打开 DEBUG 日志会看到：

```
Creating new transaction with name [null]
```

因为 `TransactionTemplate` 默认不设 `name`（声明式事务的名字是
`TransactionAspectSupport` 从"类名 + 方法名"拼出来的）。想让它有名字：

```java
TransactionTemplate template = new TransactionTemplate(transactionManager);
template.setName("audit-log");   // 日志里就变成 Creating new transaction with name [audit-log]
```

排查生产问题时这一行能省不少事。

### 12. `spring.dao.exceptiontranslation.enabled` 在 Boot 4 里已经不能写了

Boot 4.0 迁移指南原文：

> `spring.dao.exceptiontranslation.enabled` is no longer supported.
> Please use `spring.persistence.exceptiontranslation.enabled` instead.

旧名带 `level=error` 的废弃标记，写进 yml 会直接启动失败。

不过这里有个 Boot 4 自己的不一致：`DataSourceTransactionManagerAutoConfiguration`
里创建事务管理器的那段代码，读的**仍然是旧名**：

```java
// 反编译 JdbcTransactionManagerConfiguration#createTransactionManager 得到
environment.getProperty("spring.dao.exceptiontranslation.enabled", Boolean.class, Boolean.TRUE)
```

`matchIfMissing = true`，所以默认 new 出来的是 `JdbcTransactionManager`。
它继承自 `DataSourceTransactionManager`，额外做一层
`SQLException` → Spring `DataAccessException` 的翻译。

`GET /api/accounts/transaction-managers` 能直接看到这个结果：

```json
{ "count": 1, "managers": "transactionManager（JdbcTransactionManager）" }
```

顺带说明：Boot 4 里旧的 `DataSourceTransactionManagerConfiguration` 分支已被移除，
只剩 `JdbcTransactionManagerConfiguration`。

### 13. 事务管理器名字写错的报错时机

```java
@Transactional("txManager")     // 容器里只有 transactionManager
public void transfer() { ... }
```

**编译通过、启动通过**，第一次调用这个方法时才炸：

```
NoSuchBeanDefinitionException: No bean named 'txManager' available:
No matching TransactionManager bean found for qualifier 'txManager'
- neither qualifier match nor bean name match!
```

而且那笔转账根本没执行 —— **事务管理器在进方法体之前就要确定**。

为什么不在启动时发现？`ProxyTransactionManagementConfiguration` 只在
`txManager` 字段非空时才把管理器塞进拦截器，而那个字段只有实现了
`TransactionManagementConfigurer` 才会被填。默认情况下它是 `null`，
所以拦截器每次都到**调用现场**才去容器里找。

工程里的测试直接把这件事断言掉了：

```java
assertThat(interceptor.getTransactionManager())
        .as("默认没有绑定：所以名字写错不会在启动时暴露，只会在第一次调用时报错")
        .isNull();
```

另外，`value` 和 `transactionManager` 互为 `@AliasFor`：

```java
@Transactional("transactionManager")
@Transactional(transactionManager = "transactionManager")
// 完全等价，不存在"简写会走另一条路"
```

## 八、运行测试

46 个用例，全是真的连 MySQL 跑，不需要额外启动服务。

```bash
# 先重置库，保证种子数据干净
bash .workbuddy/reset-transaction-db.sh

.workbuddy/tools/mvn.sh test
```

| 测试类 | 用例数 | 覆盖什么 |
|---|---|---|
| `RollbackRuleTest` | 6 | 默认回滚规则、`rollbackFor` / `noRollbackFor` |
| `PropagationBehaviourTest` | 11 | 七个传播级别 + 各自的失败路径 |
| `TransactionalProxyFailureTest` | 17 | 五类失效场景 + 各自的修复写法 |
| `TransactionInfrastructureTest` | 12 | 容器事实、两个纠错点、只读、隔离级别、事件相位 |

**为什么必须用真实 MySQL**：这套测试里一半的断言是"某个行为真的发生了"，
而不是"框架抛了某个异常"。比如 `assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00")`
断言的是"钱留下了" —— 用 H2 跑出来的结论不能代表 MySQL + Connector/J 的行为。

**这组测试有一个特点：它们是绿的，恰恰说明坑还在。**
失效场景的"正确行为"就是失效本身。哪天框架收紧了行为、钱不退回了，
用例会变红 —— 那正是提醒我们文章该改的时候。

`TransactionInfrastructureTest` 里有两条纠错断言，可以直接当文档看：

```java
@Test
@DisplayName("纠错：@Transactional.rollbackFor 的默认值是空数组，不是「Throwable 的子类」")
void rollbackForDefaultIsEmptyArray() throws Exception {
    Method rollbackFor = Transactional.class.getMethod("rollbackFor");
    assertThat(rollbackFor.getDefaultValue()).isInstanceOf(Class[].class);
    assertThat((Class<?>[]) rollbackFor.getDefaultValue()).isEmpty();
}

@Test
@DisplayName("纠错：默认只回滚 RuntimeException 和 Error，受检异常一律提交")
void defaultRollbackRule() {
    DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
    assertThat(attribute.rollbackOn(new RuntimeException("运行时异常"))).isTrue();
    assertThat(attribute.rollbackOn(new Error("错误"))).isTrue();
    assertThat(attribute.rollbackOn(new Exception("受检异常"))).isFalse();
}
```

## 九、工程结构

```
springboot4-transaction/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/xncoding/transaction/
    │   │   ├── TransactionApplication.java          # 没有任何一行事务配置
    │   │   ├── controller/
    │   │   │   ├── AccountController.java           # 余额 / 重置 / 事务管理器清单
    │   │   │   ├── RollbackController.java          # 回滚规则 6 个
    │   │   │   ├── PropagationController.java       # 传播行为 11 个
    │   │   │   ├── FailureController.java           # 失效场景 17 个
    │   │   │   └── AdvancedController.java          # 只读 / 隔离 / 编程式 / 事件
    │   │   ├── service/
    │   │   │   ├── AccountService.java              # 无事务的基础层，被所有场景复用
    │   │   │   ├── RollbackService.java             # 回滚规则
    │   │   │   ├── PropagationInnerService.java     # 传播行为：内层
    │   │   │   ├── PropagationOuterService.java     # 传播行为：外层
    │   │   │   ├── ReadOnlyService.java
    │   │   │   ├── IsolationService.java
    │   │   │   ├── ProgrammaticService.java         # TransactionTemplate
    │   │   │   └── TransactionEventService.java     # 事件发布
    │   │   ├── event/
    │   │   │   ├── AccountChangedEvent.java
    │   │   │   ├── TransactionEventRecorder.java    # 四个相位各一个监听器
    │   │   │   └── TrapListener.java                # 反面教材，只在 listener-trap profile 加载
    │   │   ├── failure/                             # 五类失效场景
    │   │   │   ├── SelfInvocationService.java
    │   │   │   ├── NonPublicService.java
    │   │   │   ├── NonPublicCaller.java             # 同包调用方，用来够到 protected 方法
    │   │   │   ├── SwallowedExceptionService.java
    │   │   │   ├── ChildThreadService.java
    │   │   │   └── ManagerNameService.java
    │   │   ├── support/
    │   │   │   ├── TxProbe.java                     # 三层探针
    │   │   │   ├── ScenarioRunner.java              # 重置 → 执行 → 事务外读余额 + 总额
    │   │   │   └── TransactionManagerInspector.java # 列出容器里的事务管理器
    │   │   ├── mapper/                              # 全注解 SQL，无 XML
    │   │   ├── domain/ dto/ exception/
    │   └── resources/
    │       ├── application.yml
    │       └── sql/{schema.sql, data.sql}
    └── test/
        ├── java/com/xncoding/transaction/
        │   ├── RollbackRuleTest.java
        │   ├── PropagationBehaviourTest.java
        │   ├── TransactionalProxyFailureTest.java
        │   ├── TransactionInfrastructureTest.java
        │   └── TestSupport.java
        └── resources/application-test.yml
```

三个设计上的选择值得说一下：

**`AccountService` 不带事务。** 它是被所有带事务的服务复用的数据访问层。
这样事务边界全部落在调用它的方法上，"谁开的事务"在代码里一眼可见，
不会藏在某个下层方法里。

**演示方法的步骤列表从外面传进来，而不是自己 new 一个返回。**
因为有一半方法会以抛异常收场，而抛异常时返回值是拿不到的 ——
步骤存在局部变量里就会跟着异常一起丢，恰恰是"异常被 catch 住了"这种步骤最能说明问题。

**所有场景都走 `ScenarioRunner`。** 它固定做三件事，顺序不能换：
先重置、再执行、最后在事务之外读余额。

## 十、从 Spring Boot 2.0 迁过来要注意什么

老工程里的这几条现在都不成立了：

| 旧写法 | 现在 |
|---|---|
| `spring.datasource.schema` / `.data` | 改用 `spring.sql.init.schema-locations` / `.data-locations`，旧名是 `level=error` 的废弃项 |
| `spring.dao.exceptiontranslation.enabled` | 改名成 `spring.persistence.exceptiontranslation.enabled` |
| `@EnableTransactionManagement(order = 2)` | 不用手工加了，Boot 自动配置会按 `proxyTargetClass=true`（CGLIB）注册；要改默认回滚规则用 `rollbackOn` |
| 认为"非 public 方法事务失效" | `protected` / 包私有其实生效，只有 `private` / `final` / `static` 失效 |
| 认为 `readOnly = true` 只是个提示 | 在 MySQL + Connector/J 上它真的拦写 |
| `AFTER_COMMIT` 里写库不写 `REQUIRES_NEW` | Framework 6.1 起直接启动报错 |
| 断言 `rollbackFor` 默认是 `Throwable` 子类 | 默认是空数组，默认只回滚 `RuntimeException` 和 `Error` |

## 十一、许可

MIT License，署名 `Xiong Neng`。
