# springboot4-batch：使用批处理 Spring Batch

Spring Boot 4.1.1 + Spring Batch 6.0.5 的批处理示例：一个订单对账 Job，
CSV 文件 → 校验 → 写入 MySQL，坏行跳过落台账，HTTP 触发 + RFC 9457 错误响应。

署名 Xiong Neng / MIT。配套文章见仓库 `articles/` 目录第 10 篇。

## 环境

- JDK 21、Maven 3.9+
- MySQL 5.7+（默认指向 `192.168.1.97:3306`，可用环境变量覆盖），
  库 `springboot4_batch` 不存在会自动创建（`createDatabaseIfNotExist=true`）
- BATCH_* 元数据表由 Boot 自动初始化（`spring.batch.jdbc.initialize-schema: always`）
- 业务表 `recon_order` 由 `schema.sql` 幂等创建

## 快速开始

```bash
mvn spring-boot:run
```

应用启动不自动跑 job（`spring.batch.job.enabled: false`），HTTP 触发：

```bash
# 触发一次对账（201 返回，Location 指向 execution 查询地址）
curl -i -X POST "http://localhost:18080/api/jobs/recon/run?fileName=recon-orders.csv"

# 查执行状态（BATCH_JOB_EXECUTION 元数据表直查）
curl http://localhost:18080/api/jobs/recon/executions/1

# 台账：读/写/跳过计数 + 坏行明细 + 最近 5 次执行
curl http://localhost:18080/api/jobs/recon/stats

# 入库结果
curl http://localhost:18080/api/jobs/recon/orders

# 同一文件重复触发 → 409（同参数 JobInstance 已存在，幂等拒绝）
curl -i -X POST "http://localhost:18080/api/jobs/recon/run?fileName=recon-orders.csv"
```

## 拓扑与代码地图

```
data/recon-orders.csv (12 行，3 坏行)
   │ FlatFileItemReader（@StepScope，文件名来自 jobParameters）
   ▼ processor 校验：orderNo 格式 / 金额为正 / 状态白名单
   │ 不合法抛 ValidationException → faultTolerant().skip() 跳过
   ▼ JdbcBatchItemWriter → recon_order 表（chunk=4，每 chunk 一个事务）
   ▼ ReconSkipListener 坏行落台账；ReconStatsService(afterJob) 落运行快照
BATCH_* 元数据表（JobRepository 自动记账，重跑幂等的根据）
```

- `config/BatchJobConfig`：Job/Step 定义。**Batch 6 主线 API 是
  `ChunkOrientedStepBuilder`**（老 `StepBuilder`/`TaskletStep` 已弃用）；
  不写 `@EnableBatchProcessing`，让 Boot 自动配置兜底
- `controller/JobController`：触发/查询/台账/清理；409 幂等拒绝转 RFC 9457
- `service/ReconStatsService`：内存台账 + `JobExecutionListener` 快照

## Boot 4 / Batch 6 关键事实（本工程实测）

1. starter 分三层：`spring-boot-starter-batch`（默认 ResourcelessJobRepository，
   零数据库可跑）、`spring-boot-starter-batch-jdbc`（JDBC JobRepository + 元数据表
   自动建）、`spring-boot-starter-batch-jdbc-test`（= starter-batch-test + starter-jdbc-test）
2. 自动配置在 `org.springframework.boot.batch.autoconfigure` 与
   `org.springframework.boot.batch.jdbc.autoconfigure`；属性前缀 `spring.batch` /
   `spring.batch.jdbc` 没改名
3. `JobOperator` 已合并 `JobLauncher`，注入一个就够 start/restart/stop；
   `JobRepository` 兼 `JobExplorer`
4. **类大搬家**：`Job`/`JobExecution` → `core.job`，`JobParameters` →
   `core.job.parameters`，`StepExecution` → `core.step`，item 全家 →
   `core.infrastructure.item`（旧 `org.springframework.batch.item` 整包消失）
5. chunk 新实现 `ChunkOrientedStepBuilder`（5.1 实验、6.0 stable）；容错配置
   `.faultTolerant().skip(...).skipLimit(...)`，重试引擎基于 Framework 7 的
   `RetryPolicy`（不再是 Spring Retry 库）
6. 测试用 `@SpringBatchTest`（`org.springframework.batch.test.context`），
   `JobLauncherTestUtils.launchJob()` 直发、`JobRepositoryTestUtils` 清元数据

## 测试

```bash
mvn test
```

5 个用例（`ReconJobIntegrationTest`）真连 MySQL：
全绿跑通（read 5 / write 5）、坏行跳过（skip 3 且 job COMPLETED）、
同参数二次执行抛 `JobInstanceAlreadyCompleteException`、
HTTP 触发 + 409 幂等拒绝 + 404、`JobOperator instanceof JobLauncher`。

## 坑

1. **老写法编译告警/直接弃用**：`new StepBuilder(...).chunk(...)` 那套在 6.0 已
   `@Deprecated`，网上教程基本都还是老 API，照抄会带着弃用告警上线
2. **import 报错先怀疑搬家**：Batch 6 把 `JobExecution`/`JobParameters`/`item.*`
   全挪了包，IDE 自动导入到旧包不会有任何提示，直到编译失败
3. **MySQL 不自动建元数据表**：Boot 只给嵌入式库自动初始化，必须显式
   `spring.batch.jdbc.initialize-schema: always`，否则首跑报表不存在
4. **重复触发 409 是特性不是 bug**：同参数 = 同 JobInstance；要重跑就换参数
   （比如加一列 runSeq），或用 `startNextInstance` 让框架自动增量
5. `@StepScope` 的 reader 里才能解析 `#{jobParameters['fileName']}`，
   直接在单例 bean 上写 SpEL 启动就报错
