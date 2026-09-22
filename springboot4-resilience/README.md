# springboot4-resilience

Spring Boot 4 教程第 25 篇配套工程：Spring Framework 7 内置弹性能力 @Retryable 与 @ConcurrencyLimit，全部在 Boot 4.1.1 上实测。

## 技术栈

- Spring Boot 4.1.1（parent 4.1.1，Java 21，Spring Framework 7.0.9）
- @Retryable / @ConcurrencyLimit / @EnableResilientMethods（org.springframework.resilience.annotation，spring-context jar 内置，无专属 starter，无自动配置）
- spring-boot-starter-webmvc（传递 spring-context 与 spring-aop，弹性代理所需的一切已就位）
- 端口 18250（本系列统一约定，环境变量 SERVER_PORT 可覆盖）

## 业务场景

支付域最小模型，模拟不稳定的下游通道与高并发热点接口：

| 接口 | 场景 |
|---|---|
| POST /api/payments/charge?orderId=&failTimes=N | 前两轮失败第三轮成功（N<=3）或耗尽后降级兜底（N>3），返回尝试次数与耗时 |
| POST /api/payments/charge-raw?orderId=&failTimes=N | 不装兜底的裸调用，重试耗尽后原始异常冒泡转 502 |
| GET /api/payments/raw-aborts | 裸调用耗尽计数 |
| POST /api/hotspot/report | @ConcurrencyLimit(limit=2, policy=REJECT)，满员即 429 |
| POST /api/hotspot/task | @ConcurrencyLimit(limit=2, policy=BLOCK)，满员排队 |
| GET /api/hotspot/stats | 接受数 / 拒绝数 / 方法体内并发峰值 |

## 构建与运行

```bash
# 构建（跳过测试）
mvn package -DskipTests

# 运行（默认端口 18250）
java -jar target/springboot4-resilience-1.0.0.jar
```

## 验证

```bash
# 单元/端到端测试（5 个用例：重试成功、耗尽降级、异常冒泡、REJECT 拒绝、BLOCK 排队）
mvn test

# 端到端验证脚本：起服务 -> curl 取数 -> 并发压热点 -> 关服务 -> 打印取值单
bash .workbuddy/verify-resilience.sh
```

实测参考值：failTimes=2 时 attempts=3、耗时约 323ms；failTimes=5 时 attempts=4、耗时约 736ms、走降级；6 个并发请求打 REJECT 接口出现 2 个 429（InvocationRejectedException）；4 个并发请求打 BLOCK 接口全部成功、总耗时约 833ms；maxInFlight 峰值 2；应用日志 ERROR 0 行。

## 关键结论

- 弹性注解在 spring-context 的 org.springframework.resilience.annotation 包，编程式 RetryTemplate 在 spring-core 的 org.springframework.core.retry 包
- Framework 7 没有 @RetryWith，也没有 @Recover 等价物，重试耗尽后最后一次原始异常直接抛给调用方，降级由外层 try/catch 自己实现
- 启用靠手动声明 @EnableResilientMethods，Boot 4.1.1 的 AutoConfiguration.imports 中无 Resilience 相关条目
- 每次 @Retryable 方法失败都会发布 MethodRetryEvent，耗尽后追加一个 retryAborted=true 的事件

## 作者

Xiong Neng
