# springboot4-schedule：使用定时任务 Schedule

Spring Boot 4.1.1 的定时任务示例：订单业务里三个例行任务（对账文件 cron 扫描、
超时订单 fixedDelay 自动取消、每日报表 fixedRate 统计），外加停摆演示、故障注入、
动态 cron 与手动触发四类调度探针。

署名 Xiong Neng / MIT。配套文章见仓库 `articles/` 目录第 11 篇。

## 环境

- JDK 21、Maven 3.9+
- MySQL 5.7+（默认指向 `192.168.1.97:3306`，可用环境变量覆盖），
  库 `springboot4_schedule` 不存在会自动创建（`createDatabaseIfNotExist=true`）
- 业务表 `po_order` / `recon_file` / `daily_report` 由 `schema.sql` 幂等创建
- **定时任务没有任何专属 starter**：`@EnableScheduling` / `@Scheduled` 来自
  spring-context，常规 web 工程零额外依赖（Central 实测 4.1.1 无
  `spring-boot-starter-task`、无 `spring-boot-task` 模块）

## 快速开始

```bash
mvn spring-boot:run                      # 默认单线程调度池（Boot 默认行为）
SCHEDULER_POOL=4 mvn spring-boot:run     # 4 线程调度池
```

应用启动后任务即按各自节奏运行（演示用秒级间隔；生产用分钟/小时级）：

```bash
# 手动触发任意任务（与调度共用同一段业务方法，thread 字段显示执行线程）
curl -X POST http://localhost:18110/api/scheduler/trigger/recon
curl -X POST http://localhost:18110/api/scheduler/trigger/cancel
curl -X POST http://localhost:18110/api/scheduler/trigger/report

# 动态改对账扫描的 cron（下一次调度生效）
curl -X POST http://localhost:18110/api/scheduler/cron \
     -H "Content-Type: application/json" -d '{"cron":"0/2 * * * * *"}'

# 执行记录（任务、触发方式、线程、耗时、结果）
curl "http://localhost:18110/api/scheduler/journal?limit=30"

# 停摆演示：黑名单同步任务睡 8 秒，单线程池下其余任务全部停摆
curl -X POST http://localhost:18110/api/scheduler/blacklist/slow \
     -H "Content-Type: application/json" -d '{"on":true}'

# 故障注入：poison 任务每 6 秒抛一次异常（不重试、下一次照常触发）
curl -X POST http://localhost:18110/api/scheduler/chaos/poison \
     -H "Content-Type: application/json" -d '{"on":true}'
```

业务数据接口：

```bash
curl -X POST http://localhost:18110/api/orders \
     -H "Content-Type: application/json" -d '{"orderNo":"SO90001","amount":"199.50"}'
curl http://localhost:18110/api/orders/SO90001          # 15 秒后变为 CANCELLED
curl -X POST "http://localhost:18110/api/recon/files?fileName=recon-mon.csv"
curl http://localhost:18110/api/reports/today
```

## 测试

```bash
mvn test
```

18 个用例全连真实 MySQL：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| SchedulerConfigTest | 1 | taskScheduler 接管自动配置：池大小、线程名前缀 |
| OrderApiIntegrationTest | 4 | 下单 201、校验 400、查询 404、手动触发取消任务 |
| ReconFlowIntegrationTest | 5 | 登记 201、重复 409、扫描 DONE、动态 cron、非法 cron 400 |
| PoisonResilienceTest | 3 | 任务抛异常手动触发不 500、ErrorHandler 吞异常、开关复位 |
| ReportAndBlacklistIntegrationTest | 3 | 报表聚合、未生成 404、慢调用耗时 ≥ 8 秒 |
| DynamicCronTriggerTest | 2 | 动态 cron 改值生效、非法表达式拒绝 |

验证脚本（两轮取值单，正文数字同源）：`bash .workbuddy/verify-schedule.sh`

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/orders | 下单（201，校验失败 400） |
| GET | /api/orders/{orderNo} | 查询订单（404 problem+json） |
| GET | /api/orders | 订单列表 |
| POST | /api/recon/files?fileName= | 登记对账文件（重复 409） |
| GET | /api/recon/files | 文件列表与处理状态 |
| GET | /api/reports/today | 当日报表（未生成 404） |
| POST | /api/scheduler/trigger/{job} | 手动触发 recon/cancel/report/blacklist/poison |
| GET/POST | /api/scheduler/cron | 查看/修改对账扫描 cron |
| POST | /api/scheduler/blacklist/slow | 停摆演示开关 |
| POST | /api/scheduler/chaos/poison | 故障注入开关 |
| GET | /api/scheduler/journal?job=&limit= | 执行记录 |

## 实测结论（Boot 4.1.1 + Framework 7.0.9）

- 自动配置在 `org.springframework.boot.autoconfigure.task`（TaskSchedulingAutoConfiguration，
  没有拆进模块专属包）；builders/customizers 在 `org.springframework.boot.task`
- 属性前缀未改名：`spring.task.scheduling.pool.size` 默认 1（javap 构造器 iconst_1 实证），
  这是单线程停摆的根源
- 手工声明 `taskScheduler` bean 即接管自动配置：池大小、线程名前缀、
  `setErrorHandler` 三件套；异常流入 ErrorHandler（本工程记 WARN），应用日志 ERROR 0 行
- `@Scheduled` 方法抛异常不重试、不取消任务，下一次照常触发（poison 3 次失败间隔 6.0/6.0 秒实证）
- 动态 cron 用 SchedulingConfigurer + TriggerTask + 自实现 Trigger；
  `@Scheduled` 注解的 cron 在启动期定死
- 虚拟线程开启（`spring.threads.virtual.enabled=true`）时 taskScheduler 自动换成
  SimpleAsyncTaskScheduler（@ConditionalOnThreading VIRTUAL）
