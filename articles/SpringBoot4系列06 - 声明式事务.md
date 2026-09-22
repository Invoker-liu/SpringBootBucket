---
title: SpringBoot4系列06 - 声明式事务
slug: sb4-transaction
date: 2026-09-22 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, 声明式事务, Transactional, 事务传播 ]
draft: false
---

第五篇收尾时留了个话头：一个事务管理器身上，还有哪些东西是配置不出来的。这一篇回答它。

`@Transactional` 是 Spring 里最像「不用学」的注解：加一行，方法就进事务了。但它有个脾气：**用对的时候什么都不用知道，用错了它不告诉你。**

最典型的翻车现场：接口返回 422，异常栈打在日志里，调用方按失败处理。然后查库，钱已经扣了。没有报错，没有 WARN，日志上还写着「Initiating transaction commit」，事务管理器认为一切正常，按照默认规则该提交。

剩下的都是这类现场的变体：catch 住异常再包一层、方法声明成 final、把活丢给子线程、事务管理器的名字写错。能看出来的只有两处：数据库里的数据，和事务的 DEBUG 日志。前者要等对账才暴露，后者默认不输出。

所以这一篇的工程没什么业务，答案全靠看余额：工程在 `springboot4-transaction` 目录下，四十来个接口、46 个测试用例，全部连着 MySQL 跑。

![](https://static.xiongneng.me/transaction-boundary-20260921234455.png)

## 先把「没生效」定义清楚

聊失效得先定一个判定标准。「数据对不对」不好用：错法太多，全没回滚、只回滚一半、回滚了别人写的都有可能，要等查库才暴露，还分不清是事务还是业务代码的错。

我最后用的标准只有一条：这个方法运行时，线程上有没有绑着事务。这个状态可以直接读，Spring 把它放在 `TransactionSynchronizationManager` 里，纯内存，随手能问：

```java
TransactionSynchronizationManager.isActualTransactionActive()
TransactionSynchronizationManager.getCurrentTransactionName()
TransactionSynchronizationManager.isCurrentTransactionReadOnly()
TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()
```

工程里包了一层 `TxProbe`，一次把这几个值连同「当前连接号」「有没有 savepoint」一起读出来，输出成一行：

```
事务=有, 事务名=com.xncoding.transaction.service.PropagationOuterService.requiredInnerSucceeds,
只读=false, 隔离级别=跟随数据库, 连接=9144, savepoint=false
```

`连接=9144` 来自 `SELECT CONNECTION_ID()`，`savepoint` 从 `TransactionAspectSupport.currentTransactionStatus().hasSavepoint()` 读的。前者说明两笔操作是否在同一条物理连接上，后者是 NESTED 唯一靠得住的证据。

`TxProbe` 我故意写成 Bean 而不是静态工具类：静态工具类从同类里直接调就行，Bean 必须跨一次调用边界，而本篇一半的坑就出在「同类内部调用」上。

### 读余额这件事必须放在事务外面

我一开始就在这里栽过：方法内部先转账、再读余额判断「提交了没有」，读到的当然是转账后的值。未提交的事务本来就读得到自己写的值，跟它最终会不会提交没有关系。拿这个数当证据，是自己证明自己。

所以工程把读余额单独抽出来了。service 方法只记录过程、不返回余额；控制层统一走 `ScenarioRunner`，固定做三件事，顺序不能换：

```java
public ScenarioResult record(String scenario, String expectation, String conclusion, ScenarioBody body) {
    accountService.reset();          // 1. 先重置
    List<StepResult> steps = new ArrayList<>();
    try {
        body.run(steps);             // 2. 再执行业务
    } catch (Throwable e) {
        steps.add(new StepResult(...));
    }
    return accountService.build(...); // 3. 最后在事务之外读余额和总额
}
```

步骤列表从参数传进来、方法不自己返回，这是我踩出来的细节：一半方法以抛异常收场，返回值拿不到，步骤放在局部变量里会跟着异常一起丢，而「异常被 catch 住了」这类步骤最能说明问题。

### 四个账户、两对操作

演示传播行为需要内外两层。外层统一操作 1 号（熊大）到 2 号（熊二），金额 100.00；内层统一操作 3 号（熊三）到 4 号（熊四），金额 50.00。

分成两对是刻意的。我最初让内外层动同一对账户，`REQUIRES_NEW` 的新连接等在外层未提交的行锁上，实验以锁等待超时收场，看到的是 InnoDB 锁行为而不是传播行为，白跑了几轮。

分成两对之后，读结果就简单了：

| 看谁 | 余额是初值 | 余额变了 |
|---|---|---|
| 熊大（1000.00） | 外层回滚了 | 外层提交了 |
| 熊三（300.00） | 内层回滚了 | 内层提交了 |

每次还会一起返回四个账户的 `total`。转账不改变总额，这道护栏恒定成立：数字变了，说明有笔转账只落了一半。

## 4.1.1 上的事务基础设施

版本先查清楚。`spring-boot-starter-parent:4.1.1` 里锁的是这些。

| 组件 | 版本 |
|---|---|
| Spring Framework | 7.0.9 |
| `spring-tx` / `spring-jdbc` | 7.0.9 |
| HikariCP | 7.0.2 |
| MyBatis-Plus | 3.5.17（`mybatis-spring` 4.0.0、`mybatis` 3.5.19） |
| MySQL Connector/J | 9.7.0 |
| MySQL | 8.0+（本工程在 8.4 上验证） |

### 事务自动配置在一个独立模块里

`spring-boot-transaction:4.1.1` 是个独立的 jar，事务自动配置全在里面，一共三个：

```
org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration
org.springframework.boot.transaction.autoconfigure.JtaAutoConfiguration
```

它有一条条件要单独说：

```
TransactionAutoConfiguration.TransactionTemplateConfiguration.ConditionalOnSingleCandidate=PlatformTransactionManager
```

意思是：**容器里只有一个 `PlatformTransactionManager` 时，`TransactionTemplate` 自动可用**，直接注入，不用自己 new。引入第二个数据源，这个 bean 就没了，注入当场失败；工程里能直接注入它，前提就是「事务管理器唯一」，第五篇那种双数据源环境里会直接报错。

### 跟事务有关的属性一共只有三个

我翻开 `spring-boot-transaction` 的配置元数据，`spring.*` 命名空间下跟事务沾边的只有三条：

| 属性 | 类型 | 默认值 |
|---|---|---|
| `spring.jta.enabled` | Boolean | `true` |
| `spring.transaction.default-timeout` | Duration（无后缀按秒） | 无 |
| `spring.transaction.rollback-on-commit-failure` | Boolean | 无 |

`spring.transaction.default-timeout` 会被 `TransactionManagerCustomizationAutoConfiguration` 应用到每一个事务管理器上，等价于逐个调 `setDefaultTimeout`。想改默认回滚规则、指定默认事务管理器，配置项里都没有，得走注解或实现接口，后文展开。

### 一处内部不一致：事务管理器读的异常翻译属性，和元数据对不上

当前生效的属性名是 `spring.persistence.exceptiontranslation.enabled`。旧名 `spring.dao.exceptiontranslation.enabled` 写进 yml 直接启动失败，元数据里带着 `level: error`。这一半没有问题。

问题在另一头。再翻 `DataSourceTransactionManagerAutoConfiguration` 创建事务管理器的那段字节码，读的**仍然是旧名**：

```
 1: ldc  String spring.dao.exceptiontranslation.enabled
 3: ldc  class java/lang/Boolean
 5: getstatic Boolean.TRUE
 8: invokeinterface Environment.getProperty(String, Class, Object)
19: ifeq 33
22: new  org/springframework/jdbc/support/JdbcTransactionManager
33: new  org/springframework/jdbc/datasource/DataSourceTransactionManager
```

`matchIfMissing = true`，所以**默认 new 出来的就是 `JdbcTransactionManager`**。它是 `DataSourceTransactionManager` 的子类，额外做一层 `SQLException` 到 Spring `DataAccessException` 的翻译。

后果挺有意思：`spring.persistence.exceptiontranslation.enabled=false` 关得掉 `PersistenceExceptionTranslationAutoConfiguration`，关不掉事务管理器里那层 SQL 异常翻译，两处读的是不同的属性名。

有个接口专门打这件事：`GET /api/accounts/transaction-managers`，浏览器直接打开，是我排查事务问题的第一站：

![](https://static.xiongneng.me/transaction-managers-browser-20260918223145.png)

## 依赖和配置

依赖跟第二篇、第五篇是同一条线，我只挑几处相关的说。

```xml
<!-- 本篇主角。写成 spring-boot-starter-jdbc 而不是 spring-boot-jdbc，
     是因为 starter 会额外带上 HikariCP（spring-boot-jdbc 本身不含它）。
     spring-tx 是 spring-boot-jdbc 与 mybatis-plus 传递进来的，这里没有显式坐标。 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

数据访问层用 MyBatis-Plus，跟前两篇一致。本篇重点在事务、不在 ORM，我只用了 `BaseMapper` 加注解 SQL，一个 mapper XML 都没写。starter 坐标是带 `boot4` 后缀的 `mybatis-plus-spring-boot4-starter`。

配置里有两处要专门说。

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:sql/schema.sql
      data-locations: classpath:sql/data.sql
      continue-on-error: false
```

属性前缀是 `spring.sql.init.*`。写成 `spring.datasource.schema` / `.data` 会直接启动失败：那 12 个属性在元数据里全部标了 `level: error`。

另一处是本篇的取证开关：

```yaml
logging:
  level:
    org.springframework.transaction: DEBUG
    org.springframework.transaction.interceptor: DEBUG
    org.springframework.jdbc.support.JdbcTransactionManager: DEBUG
```

为什么必须手动开？事务的每一步都有日志，但一条都不在 INFO 级，默认配置下「事务到底开没开」看不见；日志长什么样，后文「事务日志怎么读」逐组列出。

建表脚本两张表，`t_account` 和 `t_tx_event`。`t_tx_event` 给事务事件用，`tx_name` 字段我留了 160 个字符：事务名是「全限定类名.方法名」，`com.xncoding.transaction.service.TransactionEventService.publishAndCommit` 就有 74 个字符，常见的 `VARCHAR(64)` 会在插入时报 `Data too long`。

## 回滚规则：默认只认 RuntimeException 和 Error

先说这一条，它是本篇最值钱的一个坑。

### 纠错：`rollbackFor` 的默认值是空数组

网上流传一个说法：`rollbackFor` 的默认值是 `Throwable` 的子类，连本仓库 `references/` 目录里的旧文都这么写。我拿字节码一验，错的。

`@Transactional` 一共 10 个注解成员（`value` 和 `transactionManager` 互为 `@AliasFor`，算一个）。字节码级确认，`rollbackFor`、`rollbackForClassName`、`noRollbackFor`、`noRollbackForClassName` 四个的默认值**都是空数组**：

| 元素 | 类型 | 默认值 | 备注 |
|---|---|---|---|
| `value` | String | `""` | 与 `transactionManager` 互为 `@AliasFor` |
| `transactionManager` | String | `""` | 同上 |
| `label` | String[] | `{}` | |
| `propagation` | Propagation | `REQUIRED` | |
| `isolation` | Isolation | `DEFAULT` | |
| `timeout` | int | `-1` | 负数表示用默认 |
| `readOnly` | boolean | `false` | |
| `rollbackFor` | Class[] | **`{}`** | |
| `rollbackForClassName` | String[] | `{}` | |
| `noRollbackFor` | Class[] | `{}` | |
| `noRollbackForClassName` | String[] | `{}` | |

空数组意味着「没有额外规则」，判定逻辑在别处。`DefaultTransactionAttribute#rollbackOn` 的字节码只有两个 `instanceof`：

```
0: aload_1
1: instanceof  java/lang/RuntimeException
7: aload_1
8: instanceof  java/lang/Error
```

**受检异常抛出去，事务照常提交。** 异常仍会正常传播给调用方，调用方以为失败了，数据却已经落库。

工程里六个接口的实测数字：

| 接口 | 抛什么 | 熊大余额 | 结论 |
|---|---|---|---|
| `/api/rollback/plain` | 不抛 | 900.00 | 对照组：事务本身是工作的 |
| `/api/rollback/runtime` | 运行时异常 | 1000.00 | 回滚 |
| `/api/rollback/checked` | 受检异常 | **900.00** | **提交，异常照抛** |
| `/api/rollback/checked-rollback-for` | 受检异常 + `rollbackFor` | 1000.00 | 回滚 |
| `/api/rollback/no-rollback-for` | 运行时异常 + `noRollbackFor` | 900.00 | 不回滚，异常照抛 |
| `/api/rollback/exception-class` | 受检异常 + `rollbackFor = Exception.class` | 1000.00 | 回滚 |

第一条是对照组，不能省，后面每一条「回滚了没有」都拿它当基准。第三条和第四条逐字对照，方法体一模一样，唯一差别是第四条多了 `rollbackFor = TransferRejectedException.class`。

![](https://static.xiongneng.me/rollback-decision-20260921234238.png)

### 同一个异常的两种命运

`/api/rollback/checked` 的完整响应长这样，注意 HTTP 状态码：

![](https://static.xiongneng.me/checked-exception-response-20260918223147.png)

异常抛了，钱也扣了，接口返回的也是失败。这就是「接口说失败、数据说成功」的来源。

规矩可以定得很简单：**只要方法签名上会出现 `throws`，`rollbackFor` 就必须写。** 团队接受「一刀切」的话，就在 `@EnableTransactionManagement` 上打开那个开关，这是 Framework 7 新加的：

```java
@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
```

`RollbackOn` 枚举只有两个常量，`RUNTIME_EXCEPTIONS` 和 `ALL_EXCEPTIONS`，默认前者。生效机制在 `AbstractTransactionManagementConfiguration#transactionAttributeSource` 的字节码里：

```java
AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource(false);
if (enableTx != null && enableTx.getEnum("rollbackOn") == RollbackOn.ALL_EXCEPTIONS) {
    source.addDefaultRollbackRule(RollbackRuleAttribute.ROLLBACK_ON_ALL_EXCEPTIONS);
}
return source;
```

`@EnableTransactionManagement` 一共只有四个属性：`proxyTargetClass`、`mode`、`order`、`rollbackOn`。**没有 `transactionManager` 属性**，指定默认事务管理器只能实现 `TransactionManagementConfigurer` 接口。

## 传播行为：七个级别，常用的只有四个

`Propagation` 枚举有七个常量：

```
REQUIRED(0) / SUPPORTS(1) / MANDATORY(2) / REQUIRES_NEW(3)
NOT_SUPPORTED(4) / NEVER(5) / NESTED(6)
```

七个全给了接口。完整结果如下，外层统一 1→2 号账户 100.00，内层统一 3→4 号账户 50.00：

| 场景 | 熊大 | 熊三 | 说明 |
|---|---|---|---|
| REQUIRED 内层失败 + catch | 1000.00 | 300.00 | 抛 `UnexpectedRollbackException` |
| REQUIRED 内层失败不 catch | 1000.00 | 300.00 | 抛 `BalanceNotEnoughException` |
| 内外层都成功 | 900.00 | 250.00 | 全部提交 |
| REQUIRES_NEW 内层失败 + catch | 900.00 | 300.00 | 外层提交成功，内层回滚 |
| NESTED 内层失败 + catch | 900.00 | 300.00 | 外层提交成功，内层退到 savepoint |
| NESTED 内层成功 | 900.00 | 250.00 | 探针读到 `savepoint=true` |
| NOT_SUPPORTED 内层 | 1000.00 | **250.00** | 外层回滚、内层留下 |
| MANDATORY 无事务 | 1000.00 | 300.00 | `IllegalTransactionStateException` |
| NEVER 事务里 | 1000.00 | 300.00 | `IllegalTransactionStateException` |
| SUPPORTS 无事务 | 1000.00 | 250.00 | 探针读到「事务=无」 |
| SUPPORTS 有事务 | 900.00 | 250.00 | 加入外层 |

### 内外层都成功、看不出区别；差别只在失败路径上

三个级别成功时结果完全一样，都是提交。只跑 happy path，选哪个都行，也就选不对。

失败路径上一分开就很清楚：

![](https://static.xiongneng.me/propagation-compare-20260921234256.png)

- **REQUIRED**：内外层是同一条事务。内层失败时事务被标成 rollback-only，外层连提交都提交不了。
- **REQUIRES_NEW**：内层是独立事务，挂起外层、另借一条连接。内层回滚不影响外层。
- **NESTED**：全程一条连接、一个事务，内层失败只是回滚到 savepoint，外层照常提交。

`REQUIRES_NEW` 和 `NESTED` 表面效果一样（外层都保住了），机制和代价完全不同。日志里一眼能分开：`Switching JDBC Connection` 只在 `REQUIRES_NEW` 这类挂起重绑的场景出现，`NESTED` 对应的是 `Creating nested transaction with name [...]`。

另一个决定性差别：`NESTED` 的外层想连内层的失败一起回滚，抛异常就行，大家还在一条事务里；`REQUIRES_NEW` 做不到，内层已经提交了。

同样是「内层成功」，探针读数可以当铁证：

```
REQUIRED    连接=9144, savepoint=false
NESTED      连接=9144, savepoint=true
```

连接号没变说明没用新连接；`savepoint` 从 `false` 变 `true` 说明打上了。这就是 NESTED 生效的全部证据。

### `REQUIRED` 里那个 catch 是无效的

七个级别里我最想单独讲的一条，写法看起来完全合理：

```java
@Transactional
public void outer() {
    move(1L, 2L, 100.00);
    try {
        inner.requiredFails();          // 内层 REQUIRED，和外层是同一条事务
    } catch (BalanceNotEnoughException e) {
        // 「我 catch 了，所以没事」
    }
    // 走到这里，提交时抛 UnexpectedRollbackException
}
```

内外层是同一条事务。内层抛异常时，事务被标成 rollback-only。**这个标记不是异常，catch 不掉。** 到提交那一刻事务管理器才发现问题，于是抛：

```
Transaction rolled back because it has been marked as rollback-only
```

这个异常的名字起得很准：「没打算回滚，但事务已经只能回滚了」。异常抛在外层的提交阶段，根因在内层那个被吞掉的异常，查的时候只能反向找。

DEBUG 日志里能提前看到征兆，三行按顺序出现：

```
Participating transaction failed - marking existing transaction as rollback-only
Setting JDBC transaction [...] rollback-only
Global transaction is marked as rollback-only but transactional code requested commit
```

**看到 `UnexpectedRollbackException`，就去内层找那个被吞掉的异常。**

外层不 catch，行为符合直觉：异常一路穿到最外面，事务按默认规则回滚，两边余额都不动。这也是最该用的写法。

### `NOT_SUPPORTED` 会造出「一笔没了、一笔留着」

七个级别里最容易让人懵的结果是这一条。外层转出 100.00、内层转出 50.00，然后外层抛异常失败。我跑完盯着余额愣了几秒：熊大 1000.00，熊三 **250.00**。

原因在 `NOT_SUPPORTED` 的定义：它把外层事务挂起，内层在自己的连接上跑，没有事务，执行完自动提交。等外层再抛异常，它已经落地了。探针读数把这件事说死了，内层那一步是：

```
事务=无, 事务名=com.xncoding.transaction.service.PropagationInnerService.notSupportedWrites,
连接=9145, savepoint=false
```

连接号是 9145，外层是 9144。两条不同的物理连接，两个各自独立的世界。

### 剩下三个：`MANDATORY` / `NEVER` 是断言，`SUPPORTS` 是适配

`MANDATORY` 要求必须已经有事务，没有就抛 `IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'`，业务代码一行都不执行。它比 `REQUIRED` 严格，适合标在「必须由外层提供事务边界」的下层方法上。

`NEVER` 反过来，要求当前不能有事务，有就抛 `IllegalTransactionStateException: Existing transaction found for transaction marked with propagation 'never'`。常用来标记「一旦被放进事务就会出问题」的方法，比如会自己调 DDL 的、依赖自动提交行为的。

`SUPPORTS` 从不主动开事务：有就用、没有就跳过。同一段代码在两种环境下探针读数完全不同：

```
无事务时调用 → 事务=无
有事务时调用 → 事务=有, 事务名=com.xncoding.transaction.service.PropagationOuterService.supportsInsideTransaction
```

适合「被事务调用时一起回滚、被非事务调用时也能跑」的查询或辅助逻辑，比如批量任务和在线接口共用的取数方法。

## 五类事务失效

前面讲的都是怎么用对，这一节是反面。五类场景的共同点：数据错了，但没有东西在喊。该抛的异常照抛、接口该返回什么返回什么，只有余额那一栏反映真实结果。其中「异常被吞掉」连异常都没有，最隐蔽。

### `this` 不是代理

出现频率最高的一个，原因不神秘：事务靠代理实现。外部调用先到代理对象，代理在方法前后开事务、提交；而 `this.chargeAndFail()` 里的 `this` 是原始对象，调用直接在对象内部发生，拦截器没有机会介入。

判断标准一句话：这次调用有没有经过那个被注入到别处的对象。

工程给了两个接口做逐字对照，业务动作完全一样，唯一差别是谁来调：

| 接口 | 熊大余额 | 结论 |
|---|---|---|
| `/api/failure/self-invocation` | 900.00 | `this` 调用，失效 |
| `/api/failure/self-invocation-fixed` | 1000.00 | 取代理再调，生效 |

修复版的 `detail` 里会打印拿到的对象类型：

```
实际类型 = com.xncoding.transaction.failure.SelfInvocationService$$SpringCGLIB$$0
```

留意 `$$SpringCGLIB$$` 这个后缀，它也是「Boot 默认用 CGLIB 代理」的直接证据，后面还会用到。

真实项目里更常见的修法是拆成两个 Bean（工程里的 `PropagationOuterService` 调 `PropagationInnerService` 就是这样），或者用 `@Lazy` 注入自身；工程里用 `getBean` 取自己，是把「拿到的是不是代理」写得最直白。

![](https://static.xiongneng.me/proxy-vs-original-20260921234311.png)

### 纠错：`protected` 和包私有上的注解是生效的

「`@Transactional` 只能写在 public 方法上」这句话流传极广，我也信过，直到在 `protected` 方法上做实验才发现它是半对半错的结论，得拆成两个独立条件看，缺一不可：

1. **Spring 会不会把它当成事务方法**，由 `AnnotationTransactionAttributeSource` 的 `publicMethodsOnly` 决定；
2. **CGLIB 能不能覆写它**，代理只能拦下自己能覆写的方法。

第一个条件就是上一节那行 `new AnnotationTransactionAttributeSource(false)`，`false` 就是 `publicMethodsOnly`。它的字段默认值是 `true`，但 `@EnableTransactionManagement` 走的不是无参构造。这一条**从 Spring 6.0 起就是这样**，四个版本（6.0、6.1、6.2、7.0）的字节码我都比过。

两个条件合起来，结果是这样：

| 修饰符 | Spring 当事务方法 | CGLIB 能覆写 | 实际结果 |
|---|---|---|---|
| `public` | 是 | 是 | 生效 |
| `protected` | 是 | 是 | **生效** |
| 包私有 | 是 | 是 | **生效** |
| `public final` | 是 | 否 | 失效 |
| `static` | 是 | 否 | 失效 |
| `private` | 是 | 否 | 失效 |

工程里三个接口的实测结果：

| 接口 | 熊大余额 | 结论 |
|---|---|---|
| `/api/failure/public-method` | 1000.00 | 生效 |
| `/api/failure/protected-method` | 1000.00 | **生效** |
| `/api/failure/package-private-method` | 1000.00 | **生效** |
| `/api/failure/static-method` | 900.00 | 失效 |

中间两行反直觉，光背结论很容易背成「非 public 全部失效」，我就在 `protected` 方法上闹过笑话、白折腾了半天。

成立的前提是走了 CGLIB 代理。把 `spring.aop.proxy-target-class` 设成 `false` 改用 JDK 动态代理，这些非 public 方法在代理对象上并不存在，会一起失效；Boot 默认值是 `true`，上表就是默认情况。

工程上的建议：`protected` 别用，跨包调用者看不到「这个方法会开事务」。

### `final` 方法上的失效，症状是 NPE

这是工程里最意外的发现，我盯着那个 NPE 看了半天，没往事务上想。

`/api/failure/final-method` 返回的是另一幅景象：

```
java.lang.NullPointerException: Cannot invoke
  "com.xncoding.transaction.support.TxProbe.summary()" because "this.probe" is null
```

报的是 NPE，一句话没提事务，看起来像依赖注入没生效，去翻 `@Autowired` 和包扫描路径什么也查不出来。

原因链有三步：

1. CGLIB 覆写不了 `final` 方法，代理类里没有它的覆盖版本；
2. 于是调用落在**代理实例自己**身上，没有被转发给目标对象；
3. Spring 用 **Objenesis** 创建 CGLIB 代理实例，**不走目标类的构造器**，代理实例自己的实例字段全是默认值，也就是 `null`。

正常调用看不出来，代理会把请求转给目标对象，字段都在它身上；只有 `final` 这种「绕过转发」的路径才会撞上。

工程里同一个方法有第二个版本，把依赖从实例字段换成方法参数：

| 接口 | 熊大余额 | 结论 |
|---|---|---|
| `/api/failure/final-method` | 无 | 抛 NPE |
| `/api/failure/final-method-with-params` | 900.00 | 失效，钱留下 |

NPE 一消失，结果就露出来了：事务没生效。结论本身是对的，只是通常以 NPE 的形式表现出来。

### 异常被吞掉，或者包成受检异常再抛

这两种都属于「异常处理没写对导致事务失效」，隐蔽程度不一样。

一种是忘记抛。回滚判定完全建立在「有没有异常穿出被代理的方法」上，catch 住不再抛，事务管理器全程不知情，到点照常提交。

另一种更隐蔽，异常抛出去了，调用方也收到了：

```java
try {
    throw new BalanceNotEnoughException("业务上失败了");
} catch (BalanceNotEnoughException e) {
    // 看起来「我明明抛出去了」
    throw new TransferRejectedException("包装后的受检异常", e);
}
```

HTTP 422 返回了，前端弹窗了，用户以为失败了。但类型从 `RuntimeException` 换成了受检异常，默认回滚规则就不认了。为匹配方法签名顺手做的一次类型转换，就能把回滚弄丢。

四种写法的实测结果摆在一起：

| 接口 | 熊大余额 | 结论 |
|---|---|---|
| `/api/failure/swallowed` | 900.00 | 吞掉异常，失效 |
| `/api/failure/rethrow` | 1000.00 | 原样重新抛，生效 |
| `/api/failure/rethrow-as-checked` | 900.00 | 包成受检异常再抛，**失效** |
| `/api/failure/swallowed-but-marked` | 1000.00 | 吞掉但标记 rollback-only，生效 |

最后一条是给「异常要转成业务返回值，但事务必须回滚」这种场景准备的：

```java
try {
    doSomething();
} catch (Exception e) {
    // 记日志、转成返回码，但必须让事务回滚
    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
}
```

注意它和「方法正常返回」并不冲突：`setRollbackOnly` 是告诉事务管理器「提交的时候改主意」，不靠抛异常。声明式的回滚只有抛异常一个出口，编程式可以，后文展开。

### 事务状态存在 ThreadLocal 里，跨不了线程

`/api/failure/child-thread` 里，主线程在事务里转出 100.00，子线程又转出 50.00，然后主线程抛异常。结果是：

**主线程那 100.00 回滚了，子线程那 50.00 留在库里。** 熊大 1000.00，熊三 250.00。

子线程读到的事务状态是「无」，另借一条连接、自动提交跑完，不报错：它这边是成功的。

工程里用的是 Java 21 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`）：**绑的是线程，不是具体哪种线程。** 换成平台线程池、`@Async`、`CompletableFuture`、并行流、消息队列消费者，结果一模一样。

正确做法是**让子线程有自己的事务边界**，把 `TransactionTemplate` 传进子线程里执行（事务状态传不过去，也不该传）：

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> future = executor.submit(() -> {
        template.executeWithoutResult(status -> {
            accountService.move(3L, 4L, CHILD_AMOUNT);
        });
        return "子线程事务内";
    });
}
```

修完的余额和没修之前一样，看起来「没区别」。这正是要点：**这修的不是原子性，跨线程没有原子性可言。** 它修的是「子线程里的多个写操作要么一起成功要么一起失败」，主事务和子事务之间的一致性得靠补偿、对账。

### 事务管理器名字写错，报错在第一次调用时

```java
@Transactional("txManager")     // 容器里只有 transactionManager
public void charge() { ... }
```

**编译通过、启动通过**，第一次调用这个方法时才炸：

```
NoSuchBeanDefinitionException: No bean named 'txManager' available:
No matching TransactionManager bean found for qualifier 'txManager'
- neither qualifier match nor bean name match!
```

这个坑躲过所有自动化检查：编译、启动都通过，冒烟测试没覆盖的接口「看起来正常」，生产上第一次走到那个分支才返回 500。

为什么不在启动时发现？`ProxyTransactionManagementConfiguration` 只在自己的 `txManager` 字段非空时才把管理器塞进拦截器，而那个字段只有实现了 `TransactionManagementConfigurer` 才会被填。默认它是 `null`，拦截器每次都到**调用现场**才去容器里找，工程里的测试直接断言掉了：

```java
assertThat(interceptor.getTransactionManager())
        .as("默认没有绑定：所以名字写错不会在启动时暴露，只会在第一次调用时报错")
        .isNull();
```

还有一条容易忽略的：写错名字时那笔转账没有执行，熊大保持 1000.00。**事务管理器在进方法体之前就要确定**，异常在方法体之前抛出，业务代码一行都没跑。看到余额没变，别当成「回滚了」，先看日志。

不写名字时走「按类型找唯一实例」，引入第二个数据源会变成 `NoUniqueBeanDefinitionException`；`value` 和 `transactionManager` 互为 `@AliasFor`：

```java
@Transactional("transactionManager")
@Transactional(transactionManager = "transactionManager")
// 完全等价，不存在「简写会走另一条路」
```

## 只读事务和隔离级别

这两个属性常被归到一类：「只是给驱动和数据库的提示，Spring 不保证什么」。前半句没错，后半句要看用什么数据库、什么驱动。

### `readOnly = true` 在这套技术栈上拦得住写

先看探针读数，两层都变了：

```
只读事务读     → Spring 声明=true, 数据库会话=只读
普通事务里读   → Spring 声明=false, 数据库会话=可读写
```

然后我往只读事务里写了一次，写入被拒绝：

```
TransientDataAccessResourceException:
### Error updating database.  Cause: java.sql.SQLException: Connection is read-only.
    Queries leading to data modification are not allowed
### SQL: UPDATE t_account SET balance = balance + ?, version = version + 1, updated_at = NOW(3) WHERE id = ?
```

关键在于**这个错是谁报的**：文案在 `com.mysql.cj.LocalizedErrorMessages.properties` 里（`Statement.27` 和 `Statement.28` 两条拼起来），由 `com.mysql.cj.jdbc.StatementImpl` 抛出。

也就是说，**拦截发生在 JDBC 驱动的客户端，不在 MySQL 服务端。** 同时 `@@session.transaction_read_only` 读出来是 1，说明 Connector/J 的 `setReadOnly` 发出了 `SET SESSION TRANSACTION READ ONLY`，这一步落到了连接上。

语义上它只是提示，能不能拦住看驱动和连接池；在这套技术栈（MySQL + Connector/J 9.7.0 + HikariCP）上它拦得住。别和「受检异常不回滚」混着记：`TransientDataAccessResourceException` 是 `RuntimeException`，顺带把事务也回滚了。

![](https://static.xiongneng.me/readonly-layers-20260921234322.png)

### `Isolation.DEFAULT` 的语义是「什么都不设」

四种声明的实测读数：

| 声明 | Spring 侧读数 | 数据库会话 |
|---|---|---|
| `Isolation.DEFAULT` | `null` | `REPEATABLE-READ`（MySQL 默认） |
| `READ_COMMITTED` | `2` | `READ-COMMITTED` |
| `REPEATABLE_READ` | `4` | `REPEATABLE-READ` |
| `SERIALIZABLE` | `8` | `SERIALIZABLE` |

这个接口的完整响应，四种声明一次看全：

![](https://static.xiongneng.me/isolation-panel-20260918223149.png)

第一行和第二行看着「结果一样」，过程完全不同：`DEFAULT` 连 `SET TRANSACTION ISOLATION LEVEL` 这句都没发，数据库保持会话默认值。换一个默认级别不是 `REPEATABLE-READ` 的数据库，两者就分开了。

成因在 `AbstractPlatformTransactionManager#prepareSynchronization`：它只在 `status.isNewSynchronization()` 为真时才执行，而 `setCurrentTransactionIsolationLevel` 又只在级别不等于 `ISOLATION_DEFAULT` 时写入，否则写 `null`。

**容易漏的前提**：隔离级别在**事务开始、借到连接之后**才落上去。当前方法加入外层已有事务时（传播行为不是 `REQUIRES_NEW` / `NESTED`），连接早就借好，这句 `SET` 不会再执行，声明的隔离级别被**静默忽略**。`readOnly` 同理。

## 编程式事务：两个和声明式相反的结论

声明式方便，但边界固定在「方法」这一级，事务没法从方法中间开始。遇到「前两步先读数据、第三步才决定要不要写」这种逻辑，就得用编程式。

工程里用的是 `TransactionTemplate`，由 `TransactionAutoConfiguration` 在「事务管理器唯一」时自动提供。

### 它能做一件声明式做不到的事

`setRollbackOnly()` 让方法正常返回、同时回滚。批量导入那种「逐条收集失败原因、最后整体回滚，但接口得返回一份明细报告」的需求，声明式写不出来，编程式一行就够了：

```java
template.executeWithoutResult(status -> {
    accountService.move(1L, 2L, AMOUNT);
    status.setRollbackOnly();       // 方法会正常返回，但事务要回滚
});
```

对应接口 `/api/programmatic/rollback-by-flag`，HTTP 200，不抛异常，熊大 1000.00，回滚了。

### 受检异常在这里必然回滚

这是两边最实质的差别，原因跟 Spring 的规则没关系。

`TransactionCallback` 的方法签名里没有 `throws Exception`，受检异常传不出 lambda，必须包成 `RuntimeException` 才抛得出去。一旦包了就是运行时异常，默认规则自然判它回滚。

于是同一个受检异常出现了两种命运：

| 写法 | 结果 |
|---|---|
| 声明式 `@Transactional` + 抛受检异常 | **提交**，熊大 900.00 |
| 编程式 `TransactionTemplate` + 抛受检异常 | **回滚**，熊大 1000.00 |

「受检异常默认不回滚」这个坑只存在于声明式事务。

### 编程式事务在日志里没有名字

打开 DEBUG 日志会看到一行奇怪的输出：

```
Creating new transaction with name [null]
```

这一次运行里它出现了 **6 次**，正好等于编程式接口数量。`TransactionTemplate` 默认不设 `name`，而声明式事务的名字是 `TransactionAspectSupport` 从「类名 + 方法名」拼出来的，上面那些 `Creating new transaction with name [com.xncoding...]` 就是它。

想让它有名字：

```java
TransactionTemplate template = new TransactionTemplate(transactionManager);
template.setName("audit-log");   // 日志里就变成 Creating new transaction with name [audit-log]
```

一堆 `[null]` 挤在日志里分不清哪个是哪个，这一行排查生产问题时能省不少事。

### 换传播行为不用拆 Bean

换一个模板对象就换了传播行为，不用为了一个事务边界把方法拆到另一个 Bean 里。

工程里同一个 `ProgrammaticService` 同时有默认模板和 `REQUIRES_NEW` 模板：

```java
TransactionTemplate requiresNewTemplate = new TransactionTemplate(transactionManager);
requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
```

`/api/programmatic/requires-new` 用它演示了「外层回滚、内层留下」：熊大 1000.00，熊三 250.00。审计日志、风控记录这类必须立刻落库的数据就是这么写的。

## 事务事件：四个相位

`@TransactionalEventListener` 有四个相位，都是字面意思，但只有跑一遍才知道它们的写入各自跟着谁走。

```
BEFORE_COMMIT / AFTER_COMMIT / AFTER_ROLLBACK / AFTER_COMPLETION
```

工程里四个相位各挂一个监听器，每个都往 `t_tx_event` 写一条。不落日志是因为「监听器在事务的哪个时刻被调用」要拉时间戳去对；落库更直接，看哪几条留下了、哪几条随事务消失，相位语义就出来了。

尤其 `BEFORE_COMMIT`，它的写入跟在事务里，事务回滚它跟着没。日志会骗人，它会打印出来；数据库不会。

![](https://static.xiongneng.me/event-phases-20260921234332.png)

三个批次的结果：

| 批次 | 事件表内容 | 条数 |
|---|---|---|
| 事务提交 | `BEFORE_COMMIT`、`AFTER_COMMIT`、`AFTER_COMPLETION`、`AFTER_COMMIT(无事务时也执行)` | 4 |
| 事务回滚 | `AFTER_ROLLBACK`、`AFTER_COMPLETION` | 2 |
| 没有事务 | `AFTER_COMMIT(无事务时也执行)` | 1 |

回滚那一次是关键证据：`BEFORE_COMMIT` 的监听器**执行了、SQL 也发出去了**，但它的写入在业务事务里，业务一回滚它就没了。

所以「在 `BEFORE_COMMIT` 里发短信、调第三方接口」是有去无回的，外部系统不会跟着回滚。有副作用的操作要放到 `AFTER_COMMIT`。

提交那一次还有个细节：事件表里有两条 `AFTER_COMMIT`，是两个不同的监听器写的，同一相位挂多个监听器时会都执行。工程里第二个监听器开了 `fallbackExecution = true`，没有事务时它也响应，这就是第三行那 1 条的来源。

`fallbackExecution` 默认值是 `false`，也就是**没有事务时事件被直接丢弃**。

### `AFTER_COMMIT` 里要写库，必须写 `REQUIRES_NEW`

这是本节最实用的一条，也是唯一会让应用起不来的。

正确写法在 `TransactionEventRecorder` 里，四个监听器的传播行为不是随便写的：

```java
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
@Transactional(propagation = Propagation.REQUIRED)          // 还在原事务里，加入它

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)      // 原事务已结束，必须新开
```

`BEFORE_COMMIT` 用 `REQUIRED`，就是要它加入原事务，才能演示「跟着一起回滚」；后面三个相位触发时原事务已经结束，必须开新事务才写得进去。不写的话，SQL 在一条没人管的连接上执行完，数据凭空消失。

工程里放了个反面教材 `TrapListener`，`AFTER_COMMIT` 配了默认的 `@Transactional`，只在 `listener-trap` profile 下加载。我用这个 profile 启动，进程退出码 1：

```
org.springframework.beans.factory.BeanInitializationException: Failed to process @EventListener
annotation on bean with name 'trapListener': @TransactionalEventListener method must not be
annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED:
public void com.xncoding.transaction.event.TrapListener.onAfterCommit(...AccountChangedEvent)
Caused by: java.lang.IllegalStateException: @TransactionalEventListener method must not be
annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED: ...
```

这条校验**从 Spring Framework 6.1 起**就有，比 Framework 7 早两个小版本，我拿 6.0、6.1、6.2 三个版本的源码比对过。「Framework 7 新增了这道校验」是个顺口的错误结论。它救的是数据：条件不满足时启动直接失败，好过静默丢数据。

## 事务日志怎么读

这一节给排错用。上面所有结论都能在日志里找到对应，前提是知道要找哪几行。

### 三组关键日志

**事务边界**，来自 `AbstractPlatformTransactionManager`：

```
Creating new transaction with name [com.xncoding.transaction.service.RollbackService.plainCommit]
Participating in existing transaction
Suspending current transaction
Suspending current transaction, creating new transaction with name [...]
Resuming suspended transaction after completion of inner transaction
Creating nested transaction with name [...]
```

`Participating in existing transaction` 是「加入外层事务」的书面证据，`Creating new transaction` 才是开了新的：以为是 `REQUIRES_NEW`，日志里写的是 `Participating`，那就是注解没写对或者没生效。

`suspend` 和 `resume` 是成对的，数量对不上就说明事务边界画错了。

**结果**：

```
Initiating transaction commit
Initiating transaction rollback
Transactional code has requested rollback
Participating transaction failed - marking existing transaction as rollback-only
Global transaction is marked as rollback-only but transactional code requested commit
```

### 本次运行的日志统计

按关键字数一遍，这一次运行的结果：

| 关键字 | 次数 |
|---|---|
| `Creating new transaction with name [null]` | 6 |
| `Participating in existing transaction` | 5 |
| `Suspending current transaction` | 8 |
| `Resuming suspended transaction` | 8 |
| `Creating nested transaction` | 2 |
| `Switching JDBC Connection` | 51 |
| `Releasing JDBC Connection` | 51 |
| `Initiating transaction commit` | 29 |
| `Initiating transaction rollback` | 24 |
| 日志里的 `ERROR` 行 | **0** |

两处可以拿来对账。提交加回滚共 53 次，连接借还 51 次，差的 2 次是那两个 `NESTED`：**没有借新连接，只在原连接上打 savepoint。** 这正是 NESTED 和 REQUIRES_NEW 的分野。`ERROR` 行是 0，几十次故意抛异常一条都没冒出来：事务日志全在 DEBUG 级，其余走 `ScenarioRunner` 的正常返回。

## 这一篇的测试怎么写

46 个用例，全部连 MySQL 跑，不需要额外启动服务。

| 测试类 | 用例数 | 覆盖什么 |
|---|---|---|
| `RollbackRuleTest` | 6 | 默认回滚规则、`rollbackFor` / `noRollbackFor` |
| `PropagationBehaviourTest` | 11 | 七个传播级别 + 各自的失败路径 |
| `TransactionalProxyFailureTest` | 17 | 五类失效场景 + 各自的修复写法 |
| `TransactionInfrastructureTest` | 12 | 容器事实、两个纠错点、只读、隔离级别、事件相位 |

这组测试有个特别的地方：**它们全绿，恰恰说明坑还在。** 失效场景的断言写的是 `assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00")`，断言的是「钱留下了」。哪天框架收紧了行为，用例会变红，那正是提醒文章该改的时候。

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

为什么必须用真实 MySQL：一半的断言是「某个行为发生了」，不是「框架抛了某个异常」。`readOnly` 拦不拦得住写、隔离级别落不落到连接上、NESTED 建不建 savepoint、子线程拿到的是不是另一条连接，都依赖具体数据库和驱动，用 H2 跑出来的结论不能代表 MySQL。

复现只要一条命令：工程开着 `spring.sql.init.mode=always`，每次起上下文都会把两张表刷回种子数据，不用手工清库：

```bash
mvn test
```

## 避坑指南

**坑一，`rollbackFor` 的默认值是空数组，不是「Throwable 的子类」。**

方法签名出现 `throws` 就写 `rollbackFor`，或一刀切打开 `@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)`。

**坑二，catch 之后包成受检异常再抛，等于白抛。**

要么原样重抛，要么吞掉异常但调 `setRollbackOnly()`。

**坑三，内层失败被外层 catch 住，提交时会抛 `UnexpectedRollbackException`。**

看到这个异常，就去内层找被吞掉的异常。

**坑四，「非 public 方法事务失效」是半对半错的结论。**

`publicMethodsOnly` 传的是 `false`，从 Spring 6.0 起就这样；CGLIB 又能覆写 `protected` 和包私有方法。前提是走 CGLIB 代理。

**坑五，`final` 方法上的事务失效，症状是 NPE 而不是数据不一致。**

**坑六，`this` 不是代理。**

修复方式是拆 Bean、`@Lazy` 注入自身，或者从容器里取一次。

**坑七，事务状态存在 `ThreadLocal` 里，跨不了线程。**

修法是给子线程自己的 `TransactionTemplate`；跨线程没有原子性，一致性得靠补偿和对账。

**坑八，`AFTER_COMMIT` 里写库必须写 `REQUIRES_NEW`，这不是优化建议。**

**坑九，`readOnly = true` 的拦截发生在 JDBC 驱动的客户端，不在数据库。**

别当安全机制用。

**坑十，`Isolation.DEFAULT` 的语义是「什么都不设」。**

**坑十一，隔离级别和 `readOnly` 只在「新事务」里下得去。**

想让它们生效，传播行为必须是 `REQUIRES_NEW` 或 `NESTED`。

**坑十二，编程式事务里受检异常必然回滚，和声明式相反。**

**坑十三，编程式事务在日志里没有名字。**

想分得清就自己 `template.setName("...")`。

**坑十四，`NOT_SUPPORTED` 会造出「一笔没了、一笔留着」。**

**坑十五，事务管理器名字写错的报错时机是「第一次调用」，不在启动。**

## 小结

选型建议三句话。日常业务方法，默认的 `REQUIRED` 加一条纪律就够：方法签名出现 `throws` 就写 `rollbackFor`，或者全局打开 `RollbackOn.ALL_EXCEPTIONS`。要「部分提交、部分回滚」，先想 `NESTED`，同一条连接省一次借还；连接吃紧或内层必须独立提交时再上 `REQUIRES_NEW`。跨线程的写入别指望继承事务，给子线程、消息消费者自己的 `TransactionTemplate`。

没解决的事也有两件：一是 `NOT_SUPPORTED` 挂起外层之后的行锁竞争，我只验了单线程下的结果，没有压测数据；二是事务事件在多监听器加异步监听器组合下的执行顺序，工程里只覆盖了单相位单监听器的情形，这两块等撞上真实场景再补。

`P-1` 这一批六篇到这里收齐了：RESTful 接口、MyBatis-Plus、Spring Data JPA、MongoDB、多数据源、声明式事务。前五篇把数据访问这一层铺开，这一篇把「数据改完之后到底提交了没有」收了个尾。六篇的工程都在仓库根目录，每篇自带 README，能单独跑。

失效场景那几个接口建议你亲手跑一遍，看一遍余额比读十遍结论有用。

署名 `Xiong Neng`，许可 MIT。


## 参考链接

- [Spring Framework 7.0 - 事务管理](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Spring Framework 7.0 - @Transactional 注解语义](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Framework 7.0 - 事务事件](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Spring Framework 7.0 - 声明式事务管理](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
