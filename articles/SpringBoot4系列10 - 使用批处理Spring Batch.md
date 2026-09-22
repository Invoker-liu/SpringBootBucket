---
title: SpringBoot4系列10 - 使用批处理Spring Batch
slug: sb4-batch
date: 2026-10-10 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, SpringBatch, 批处理 ]
draft: false
---

上一章的 RabbitMQ 解决的是「通知得可靠」，这一章解决「算得完」。场景还是订单：每天凌晨把当天的对账文件倒进数据库，月底把全量用户跑一遍账单，每个季度把失效数据归档。这类活的共同点是数据量大、允许延迟、绝不允许算一半。你可以接受结果晚几个小时出来，但不能接受一个算了一半的结果被当成完整结果用。

批处理和实时接口是两种脾气。实时接口怕慢，批处理怕断；实时接口一个请求一个事务，事务边界是框架划好的，批处理一口气吞几万条，事务边界得自己划。拿写接口的思路去写批处理，多半要吃亏，我就是吃过亏才学的乖。

我一个支付项目的对账模块就闹过这样的笑话：一个 for 循环，从文件里读一行，校验一下，往库里插一行，全部插完打印一句「对账完成」。逻辑上挑不出毛病，跑起来也能跑。直到有一天财务说数据少了三万条，我排查了一整晚，最后发现文件中间有一行格式是坏的，代码直接抛异常，整个循环当场死了，前面插进去的两万条留在库里，没有回滚，也没有任何记录说这次对账只跑了一半。说实话，跑了半截的账比没跑更可怕，因为没人知道它跑到了哪，下一次补跑也不知道该从哪一行接着来。

![](https://static.xiongneng.me/batch-recon-lifecycle-20260922012409.png)

这就是批处理要解决的事：读、处理、写要有事务边界，坏数据要有去处，跑没跑完要有记录，跑挂了要能接着跑。Spring Batch 把这四件事全做成了框架能力，Spring Boot 4 配的是 Spring Batch 6。这篇全按 6 的写法来，文中所有行为我都在 Boot 4.1.1 加 Batch 6.0.5 的组合上实测过，正文里出现的每个数字都来自那几轮真实运行。

工程在 `springboot4-batch` 目录，场景是订单对账：CSV 文件进，MySQL 出，三条坏行跳过落台账，HTTP 触发，五个测试用例全连真实 MySQL 跑。对账这个场景不大，但批处理的四个关切它一个不缺，拿来讲机制刚好。

## Spring Batch 6 的编程模型

### starter 按存储拆成三层

Boot 4 的批处理 starter 不再只有一个。4.1.1 里实际存在三个，我把它们的 POM 逐个拆开看过，结构是这样的：

```text
spring-boot-starter-batch
├── spring-boot-starter
└── spring-boot-batch              ← 编程模型 + 自动配置

spring-boot-starter-batch-jdbc
├── spring-boot-starter
├── spring-boot-starter-batch
├── spring-boot-starter-jdbc
└── spring-boot-batch-jdbc         ← JDBC 版 JobRepository + 元数据表初始化

spring-boot-starter-batch-jdbc-test
├── spring-boot-starter-batch-test
└── spring-boot-starter-jdbc-test
```

拆分的关键在默认行为。Batch 6 的默认 JobRepository 是 `ResourcelessJobRepository`：不配数据库也能跑批，执行记录不走任何存储。挺有意思的一个取舍，很多应用要的只是「分块 + 容错」这套批处理模型，执行历史对它们没有价值，那就不必为了一个数据库连接付出部署成本。代价也得认：记录不落库，重启就没有断点可续，同一个参数跑两遍框架也拦不住你。需要重跑幂等、断点续跑、执行历史这些能力，就显式上 `starter-batch-jdbc`，把 `BATCH_*` 元数据表落到真数据库里。

还有个 `spring-boot-starter-batch-data-mongodb`，把执行记录记到 MongoDB，思路一样，这篇不展开。测试侧也有对应的拆分，`-test` 结尾的 starter 就是本体加测试工具的合体，后面写测试那节会用到。

怎么选？问自己一个问题：这次跑挂了，你需不需要知道它跑到哪了。不需要，第一个 starter 就够，部署还轻；需要，就上 jdbc 版。我在两个项目里分别走过这两条路，判断标准一直是这一条，没变过。

### 接口合并后的运行时角色

Spring Batch 的运行时角色有四个：JobRepository 管执行记录的写入，JobExplorer 管查询，JobLauncher 管启动，JobOperator 管启停和运维操作。Batch 6 用接口继承收拢了它们的注入面：

```text
JobRepository  extends JobExplorer
JobOperator    extends JobLauncher
```

业务代码里注入一个 `JobOperator`，启动、重启、停止、查询执行历史，全在这一个对象上。我第一次看到这条继承关系还愣了一下，反应过来之后觉得这刀切得干净：四个角色一个没少，职责还是各管各的，但你要注入的东西只剩两个，写查询逻辑时也不再需要在两个对象之间来回翻。

### 类在哪个包

写代码前先确认 import。Spring Batch 6 里几个天天要写的类，包位置我整理成一份事实清单：

```text
Job                org.springframework.batch.core.job
JobExecution       org.springframework.batch.core.job
JobParameters      org.springframework.batch.core.job.parameters
StepExecution      org.springframework.batch.core.step

ItemReader / FlatFileItemReader / JdbcBatchItemWriter …
                   org.springframework.batch.infrastructure.item.*
```

item 全家整体住在 `infrastructure.item`，不跟 core 混在一起。从资料里抄 import 是我这一篇踩到的最大的坑，网上能搜到的示例代码十有八九带着旧包名，编译器报「找不到符号」的时候，先核对包名，别急着怀疑自己写的逻辑。这份清单是我把工程里实际用到的 import 逐个对着源码包路径核出来的，不是从文档抄的，Batch 6 的二手资料里旧包名太多，抄之前最好都过一遍。

### ChunkOrientedStepBuilder

chunk 模型的 builder 是 `ChunkOrientedStepBuilder`，构造参数三个：step 名、JobRepository、chunk 尺寸。事务管理器有独立的 `.transactionManager()` 挂点，chunk 尺寸只由构造参数决定，别处配了也不算数。容错配置从 `.faultTolerant()` 进入，先开总开关，再挂 `.skip()` 和 `.skipLimit()`，跳过的语义落在 `SkipPolicy` 接口上。

一个 chunk 的生命周期值得在纸上走一遍：reader 一条一条读，processor 一条一条过，框架攒着不提交；攒满 4 条，writer 一次写入，事务提交，元数据表里的计数跟着更新。中途任何一步炸了，这个事务整体回滚，框架再按容错配置决定是跳过坏行重来，还是整块失败。把这段走通了，后面的 skip、断点续跑就都是自然推论。

chunk 尺寸就是事务的粒度。给小了事务开销大，给大了单块回滚的代价高，对账这种量级我取 4，纯粹是为了让后面的实测数字好观察。

### 容错的重试引擎

chunk 容错的重试用的是 Framework 7 的 `RetryTemplate`，`.retryPolicy()` 收 `org.springframework.core.retry.RetryPolicy`。上一篇 RabbitMQ 的消费重试走的是同一个引擎。重试在 Boot 4 里是平台能力，各模块共用同一套语义，你在批处理里调熟的重试参数，到消息消费那边照样认识。这篇的重点在跳过不在重试，对账的坏行重试一万次也不会变好，所以重试我只交代出处，不展开配置。

## 依赖和配置

starter 我用 `spring-boot-starter-batch-jdbc`，因为这篇要把执行记录落 MySQL：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- 测试：starter-batch-test（spring-batch-test）+ starter-jdbc-test 的合体 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch-jdbc-test</artifactId>
    <scope>test</scope>
</dependency>
```

三个依赖各干各的：batch-jdbc 带编程模型和 JDBC 版 JobRepository，connector-j 只在运行期连库，test starter 把 `spring-batch-test` 和 JDBC 测试支撑一起带进来，不用再单独引。

MySQL 在我树莓派的 Docker Compose 里跑着，IP 是 192.168.1.97，库名 springboot4_batch。本机跑批、五个测试用例、后面所有实测，连的都是这同一个库，不存在本地一套、测试一套的口径差。

配置文件里有个必须显式声明的开关：

```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.1.97:3306/springboot4_batch?...
  batch:
    job:
      enabled: false        # 不让应用一启动就跑 job
    jdbc:
      initialize-schema: always
```

`batch.job.enabled` 设成 false 是因为这篇用 HTTP 触发，应用一启动就自动跑 job 不是我要的行为。`initialize-schema` 我要单独拎出来说：Boot 的元数据表初始化默认只认嵌入式数据库，H2 之类开箱即建，MySQL 这种外挂数据库默认不动。少了这一行，第一次跑批就报 `BATCH_JOB_INSTANCE` 表不存在的 BadSqlGrammar，我第一次跑就栽在这。更隐蔽的是属性前缀：这个属性挂在 `spring.batch.jdbc` 前缀下，写到顶层 `batch.jdbc` 下面会静默不生效，应用照常启动，连个 warning 都没有，九成是以为配置类没装配，实际是建表那步被跳过了。属性前缀拿不准的时候，javap 一下 `BatchJdbcProperties`，注解里的字符串就是答案。

![](https://static.xiongneng.me/batch-starter-anatomy-20260922012413.png)

## 核心代码

### Job 定义，一个 builder 走到底

整个对账 Job 集中在一个 `@Bean` 方法里，step 和 job 一口气搭完：

```java
@Bean
public Job reconJob(JobRepository jobRepository,
                    PlatformTransactionManager transactionManager,
                    FlatFileItemReader<OrderRow> reader,
                    JdbcBatchItemWriter<OrderRow> writer,
                    ReconStatsService stats) {
    var step = new ChunkOrientedStepBuilder<OrderRow, OrderRow>(
                    "reconStep", jobRepository, 4)
            .reader(reader)
            .processor(row -> { stats.onRead(); return validate(row); })
            .writer(items -> { writer.write(items); items.forEach(i -> stats.onWrite()); })
            .transactionManager(transactionManager)
            .faultTolerant()
            .skip(ValidationException.class, FlatFileParseException.class)
            .skipLimit(10)
            .skipListener(new ReconSkipListener(stats))
            .build();

    return new JobBuilder(BatchJobConfig.JOB_NAME, jobRepository)
            .start(step)
            .listener(stats)
            .build();
}
```

三个配置点值得说。事务管理器是独立的 `.transactionManager()`，chunk 尺寸只由构造参数决定，这里是 4。容错先过 `.faultTolerant()` 总开关，再用 `.skip()` 和 `.skipLimit()` 细化：跳过的异常类型是 `ValidationException` 和 `FlatFileParseException` 两种，前者是业务校验不过，后者是 CSV 那一行列数对不上，上限给了 10。`.skipListener()` 收一个普通对象，这里的 `ReconSkipListener` 同时实现了 `SkipListener` 和 `StepExecutionListener` 两个接口，step 开始时把 `jobExecutionId` 交给台账，坏行才有归属。没有这个 id，台账里的坏行就不知道自己属于哪一次执行。

job 这一侧就两件事：`JobBuilder` 把 step 挂上去，`.listener(stats)` 挂一个 job 级监听器。`ReconStatsService` 既在 processor 和 writer 里当计数器，又当 job 监听器，job 跑完它手里就有全套数字，台账接口直接从它取值。一个 bean 身兼两职，省掉了中间传状态的代码。

校验逻辑就是个普通函数，金额非正、状态不在白名单、订单号格式不对，抛 `ValidationException`：

```java
static OrderRow validate(OrderRow row) {
    String bad = null;
    if (row.getOrderNo() == null || !row.getOrderNo().matches("SO\\d{4,}")) {
        bad = "orderNo 缺失或格式非法";
    }
    try {
        if (bad == null && new BigDecimal(row.getAmount())
                .compareTo(BigDecimal.ZERO) <= 0) {
            bad = "金额必须为正";
        }
    } catch (NumberFormatException e) {
        bad = "金额不是数字";
    }
    if (bad == null && !Set.of("PAID", "REFUND", "SETTLED").contains(row.getStatus())) {
        bad = "状态不在白名单";
    }
    if (bad != null) {
        throw new ValidationException("坏行 [" + row + "]: " + bad);
    }
    return row;
}
```

规则本身没什么可说的，三条检查，哪条不过就把原因记进 `bad`，最后统一抛。注意这里没有 try-catch 兜底，没有降级，就是抛。坏数据的去处不归 processor 管，归容错配置管。这是批处理和业务代码的一个心态差别，我一开始也没扭过来，总想在 processor 里把一切处理得体面，后来才想明白：processor 只负责把坏行喊出来，怎么处置是容错层的事。

### reader 参数化，文件名从 jobParameters 来

对账文件每天一个，reader 不能写死，标准做法是 `@StepScope`：

```java
@Bean
@StepScope
public FlatFileItemReader<OrderRow> reconReader(
        @Value("#{jobParameters['fileName']}") String fileName) {
    return new FlatFileItemReaderBuilder<OrderRow>()
            .name("reconReader")
            .resource(new ClassPathResource("data/" + fileName))
            .linesToSkip(1)
            .delimited(config -> config.names("orderNo", "merchant", "amount", "status"))
            .targetType(OrderRow.class)
            .strict(true)
            .build();
}
```

`#{jobParameters['fileName']}` 这个 SpEL 表达式只在 step 作用域能解析，reader 这个 bean 每次 job 启动才创建，参数自然就进来了。`linesToSkip(1)` 跳过 CSV 的表头，`.delimited()` 按列名把字段映射到 `OrderRow` 的属性上。`FlatFileItemReaderBuilder` 的 `.delimited()` 支持传 lambda，写成 `.delimited(config -> config.names(...))`，比记一长串链式方法省事。`.strict(true)` 是我故意开的：文件不存在直接失败。对账这种事宁可失败，也不能静默跑一个空批出来。

参数里只放文件名还有一个好处：JobInstance 的唯一性就是 job 名加全部参数，文件名当参数，同一个文件天然只有一个实例，幂等判定白送。要是把时间戳掺进参数里，每次触发都是新实例，幂等防线立刻失效。参数不是越多越好，够用就好。

### writer，一个 SQL 搞定

```java
@Bean
public JdbcBatchItemWriter<OrderRow> reconWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<OrderRow>()
            .dataSource(dataSource)
            .sql("""
                 INSERT INTO recon_order
                   (order_no, merchant, amount, status, recon_time)
                 VALUES (:orderNo, :merchant, :amount, :status, NOW())
                 """)
            .beanMapped()
            .assertUpdates(true)
            .build();
}
```

`.beanMapped()` 让 SQL 里的命名参数直接对上 `OrderRow` 的属性名，一行手写映射都不用。`.assertUpdates(true)` 让每条写库的影响行数必须是 1，否则抛异常。批处理最怕的是「以为写进去了，库里没有」，这个开关把静默失败变成显式崩溃，稳稳的赚。SQL 里那个 `NOW()` 是入库时间，回头排查时拿它跟元数据表里的执行时间对轴，一眼就能看出哪一批是谁。

![](https://static.xiongneng.me/batch-job-topology-20260922012416.png)

把整条链路摆在一起看：reader 按 chunk 拉数据，processor 校验，writer 入库，每 4 条一个事务提交一次；坏行在容错层被 skip 接住落进台账，框架自己往 `BATCH_*` 五张元数据表里记每一笔执行记录。业务数据走上面的管道，执行记录走下面的表，两条线互不干扰，缺一条就不叫批处理。你写的代码只长在上面那条线，下面那条线全是框架在记。

### 容错跳过的完整去向

![](https://static.xiongneng.me/batch-skip-flow-20260922012421.png)

一条坏行的旅程是这样的：processor 抛出 `ValidationException`，容错层拿 `SkipPolicy` 问一句「该跳吗」，异常类型在 skip 清单里、计数没到 `skipLimit`，答案是可以；这一条被记进 skip 统计，chunk 里剩下的行照常写库，坏行本体转交 skipListener 落台账。整个过程业务代码无感，但台账里有名有姓。我测试时故意喂了一条金额是字母的行，跑完翻台账，它和另外两条坏行并排躺着，行内容加跳过原因，一条不少。

要是异常不在清单里，或者跳过数已经到上限，答案就是不行，整个 chunk 回滚，job 以失败告终。这个兜底很重要：skip 是给「已知的坏」准备的，出了清单范围的意外，框架宁可整块失败也不会替你悄悄扔数据。

### 触发，JobOperator 一个入口

启动任务就一个门：

```java
var execution = jobOperator.start(reconJob,
        new JobParametersBuilder()
                .addString("fileName", fileName)
                .toJobParameters());
```

参数只用了一个 `fileName`，字符串类型。`JobParametersBuilder` 还支持日期、长整型这些类型，参数越丰富，唯一性判定就越精确。同步执行，方法返回的时候 job 已经跑完，`execution` 里就是终态。HTTP 层包一层就是完整的触发接口：控制器把 fileName 接下来转手给 `jobOperator.start()`，响应 201，`Location` 头指向这次执行的查询地址，客户端拿着它去查执行详情。幂等的活框架已经干完了，同一个 `fileName` 参数第二次触发会抛 `JobInstanceAlreadyCompleteException`，我把它翻成 409：

```java
} catch (JobInstanceAlreadyCompleteException e) {
    throw new BusinessException(HttpStatus.CONFLICT,
            "该文件已对账完成（同参数的 JobInstance 已存在），重复触发被拒绝");
}
```

![](https://static.xiongneng.me/batch-idempotent-path-20260922012425.png)

这个 409 有明确的语义支撑：一个 JobInstance 由 job 名加参数唯一确定，跑完就是终态，想重跑就换参数。`BATCH_JOB_INSTANCE` 表里那一行记录就是「这个文件已经对过账」的铁证，比任何业务代码里的 if 都可靠。业务代码的 if 会被人改掉，元数据表里那行记录不会。

## 完整案例，跑起来看

对账文件 12 行，我故意埋了 3 条坏行：一条负金额，一条状态是白名单外的 `CANCELLED`，一条金额是字母。

```text
SO10004,拼多多,-10.00,PAID      ← 负金额
SO10006,淘宝,88.00,CANCELLED    ← 状态不合法
SO10010,唯品会,abc,PAID         ← 金额不是数字
```

三条坏行各踩一条校验规则，正好把 skip 链路的三种原因都喂了一遍。位置也不是随便挑的：按文件顺序，SO10004 落在第一个 chunk，SO10006 落在第二个，SO10010 落在第三个，三条坏行分散在三个事务块里，好行的提交互不受牵连。

启动应用，触发一次对账：

```bash
curl -i -X POST "http://localhost:18080/api/jobs/recon/run?fileName=recon-orders.csv"
```

返回 201，`Location` 指向这次执行的查询地址。跑完看台账：

![](https://static.xiongneng.me/batch-run-stats-20260919195001.png)

12 读、9 写、3 跳过，job 状态 `COMPLETED`。跳过的三条都有明细，行内容和跳过原因一一对应：SO10004 是负金额，SO10006 是状态不在白名单，SO10010 是金额不是数字。这个结果跟我在文件里埋的坏行完全对得上，三个数加一个状态，把这次执行的骨架说完了。

同一个请求我再发一次：

![](https://static.xiongneng.me/batch-repeat-rejected-20260919195005.png)

409，`application/problem+json`，`type` 是 `urn:problem-type:business-rule-violation`，跟系列前几篇的错误响应是同一套骨架。

最硬的证据在数据库里。`BATCH_STEP_EXECUTION` 表里躺着框架自己记的账：

```text
STEP_NAME   READ_COUNT  WRITE_COUNT  PROCESS_SKIP_COUNT  COMMIT_COUNT
reconStep   12          9            3                   3
```

chunk 尺寸 4，12 行数据 3 次提交，读写跳过三个数跟台账严丝合缝。这张表是框架写的，应用代码没有碰它，我特意全局搜了一遍工程，没有任何一处 SQL 指向 `BATCH_` 开头的表。job 挂在半路，这张表知道它死在哪一个 chunk，重启时从哪里继续。这就是前面说的「跑挂了要能接着跑」的物质基础。

顺带翻了一眼另外两张表：`BATCH_JOB_INSTANCE` 里这个文件一行，`BATCH_JOB_EXECUTION` 里这次触发一行。表不多，每张只回答一个问题：实例是谁、执行得怎么样、step 干了多少活。查问题的时候顺着外键从 instance 走到 step，一次执行的来龙去脉几分钟就能拼出来。

想自己复现的话，工程 README 里写了启动与触发步骤，照着来一遍，台账、409、元数据表三样证据就都是你自己的了。

## 这一篇的测试怎么写

五个用例我全连了真实 MySQL：

```java
@SpringBootTest
@AutoConfigureMockMvc
@SpringBatchTest
class ReconJobIntegrationTest {

    @Autowired
    private JobOperatorTestUtils launcher;

    @BeforeEach
    void cleanUp() {
        repositoryUtils.removeJobExecutions();
        jdbcTemplate.update("DELETE FROM recon_order");
    }

    @Test
    void jobReadsValidatesWritesAndAccounts() throws Exception {
        JobExecution execution = launcher.startJob(params("recon-test-ok.csv"));
        assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    }
}
```

每个用例跑之前，`@BeforeEach` 先把元数据表里的执行记录和业务表里的对账结果清掉，不然上一轮留下的 JobInstance 会让同参数的触发直接 409。清理走的是框架给的 `removeJobExecutions()`，不是手写 DELETE，元数据表之间有外键，手工删顺序错了一身麻烦。

`@SpringBatchTest` 提供的测试工具类是 `JobOperatorTestUtils`，启动方法 `startJob()`，跟生产代码用 `JobOperator` 的姿势一致。测试文件也走 classpath 读取，跟生产同一条路径，测出来的就是生产行为。写测试就是在预演生产的启停方式，生产代码注入什么，测试里就操练什么。

五个用例的分工是这样的：第一个跑完整链路，读、校验、写、落台账一条龙，断言终态 `COMPLETED`。第二个喂坏文件，断言 skip 计数是 3 而且状态照样 `COMPLETED`，这是容错语义的核心：**跳过不是失败，容错跑完就是成功**。第三个用例验证同参数二次执行抛 `JobInstanceAlreadyCompleteException`，幂等防线钉死在测试里。第四个用例走 MockMvc 打 HTTP 面，201、409、404 一个个过，把触发接口的三种出路全盖住。第五个用例只有两行，断言 `JobRepository instanceof JobExplorer`，把接口合并这件事钉在测试里，将来升级批次要是有变化，测试第一个知道。

## 避坑指南

**坑一，`initialize-schema` 写错前缀，静默不生效。** 正确位置是 `spring.batch.jdbc.initialize-schema`。写到顶层 `batch.jdbc` 下，应用正常启动，第一次跑批直接 `BadSqlGrammar: BATCH_JOB_INSTANCE does not exist`。错前缀在 Boot 里常见，这一处错得特别安静，因为配置类照样装配，只是建表那步被跳过了。前缀拿不准就 javap 一下 `BatchJdbcProperties`，注解里的字符串就是答案。

**坑二，import 抄错包名，编译器第一个知道。** 常用类的包位置我抄在这里：`Job` 和 `JobExecution` 在 `core.job`，`JobParameters` 在 `core.job.parameters`，`StepExecution` 在 `core.step`，item 全家在 `infrastructure.item`。资料里的 import 与这份清单对不上时，以清单为准，别硬猜。

**坑三，`@StepScope` 不加，SpEL 就是个死字符串。** `#{jobParameters['fileName']}` 只有在 step 作用域的 bean 里才会被解析，普通单例 bean 上写这个，启动时 reader 拿到的是字面量，文件找不到直接失败。反过来，加了 `@StepScope` 的 reader 别在启动时就去访问它，参数还没来。

**坑四，嵌入式数据库的默认值会骗人。** 本地用 H2 跑得好好的，元数据表自动建，一到测试环境的 MySQL 就报表不存在。Boot 对嵌入式库的默认初始化在换库时全是坑，凡是非嵌入式数据源，建表的事都自己声明一遍。

**坑五，同参数 409 是语义，是幂等防线。** 测试环境连着生产库调试的时候，同一个文件反复触发会一直 409，容易误判成 bug。处理办法只有两个，换参数（加一个 runSeq 之类的参数位），或者清 `BATCH_JOB_INSTANCE` 及其关联表。清表是危险动作，`BATCH_*` 五张表有外键关联，顺序删错会把元数据表搞脏，测试代码里用框架给的 `JobRepositoryTestUtils.removeJobExecutions()` 最稳。

**坑六，坏行跳过要有台账，否则容错变成吞错。** `.skip()` 配好了，坏行不挡路，但它也消失了，框架不给默认的记录。skipListener 不是可选项，跳过了什么、为什么跳，必须落在一个能查到的地方。台账可以先用内存 Map 加一张数据库表，演示够了；生产上把坏行单独落一张表，是批处理上线前的最后一道必答题。

**坑七，`batch.job.enabled` 忘了关，应用一启动就跑 job。** Boot 默认应用启动时自动执行容器里的 job，这篇的 reader 带 `.strict(true)`，参数里没有 fileName，文件找不到当场失败，启动日志里一个资源异常，跟应用本身没半点关系。HTTP 触发的应用，这个开关必须显式关掉。

**坑八，参数里掺时间戳，幂等防线就废了。** JobInstance 由 job 名加全部参数共同确定，参数里加一个 `new Date()`，每次触发都是新实例，同参数 409 永远打不出来。想区分「重跑同一个文件」和「跑新的文件」，该换的是 fileName，不是往参数里塞时间。

## 小结

选型建议一句话能说清：轻量场景用默认的 `ResourcelessJobRepository` 起跑，一个数据库都不用配；要执行历史、重跑幂等和断点续跑，就上 `starter-batch-jdbc`，元数据表的事交给框架。入口统一收在 `JobOperator`，chunk 用 `ChunkOrientedStepBuilder` 搭，测试配 `JobOperatorTestUtils`，这套组合我整篇验证下来是顺的，照抄不会踩到结构性问题。

没解决的事也有两件。一是台账目前还是演示级的内存 Map 加一张表，生产上要把坏行单独落表，配上查询和补录的口子。二是这篇只跑了单进程串行 chunk，远程分区和并行 step 能把吞吐再抬一个量级，等我真的有超大文件要跑，再回来补测一篇。

## 参考链接

- [Spring Batch 6.0 What's New](https://docs.spring.io/spring-batch/reference/whatsnew.html)：本文 Batch 6 编程模型与容错配置的官方说明
- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/index.html)：JobRepository 与元数据表结构的权威文档
- [Spring Boot 4.1 Reference](https://docs.spring.io/spring-boot/index.html)：batch 章节的属性清单，`spring.batch.jdbc.*` 前缀以此为准
- [spring-boot-starter-batch-jdbc 4.1.1](https://central.maven.org/maven2/org/springframework/boot/spring-boot-starter-batch-jdbc/4.1.1/)：本文 starter 三层结构的 POM 实证来源
