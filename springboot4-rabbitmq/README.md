# springboot4-rabbitmq —— Spring Boot 4 集成 RabbitMQ

《SpringBoot4 全家桶》系列第 09 篇配套工程（P-2 批次）。订单域异步消息场景：下单/支付发布事件，通知服务消费，覆盖 JSON 消息转换、发送方 confirm、自动重试、死信队列兜底四条主线。

> 系列索引见仓库根 `README.md`，配套文章见 `articles/SpringBoot4系列09 - 使用消息队列RabbitMQ.md`。

## 一、环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 工程按 `java.version=21` 编译 |
| Spring Boot | 4.1.1 | `spring-boot-starter-parent` 托管全部版本 |
| RabbitMQ | 4.x | 本系列实测 4.3.6（`rabbitmq:4-management` 镜像） |

中间件地址在 `src/main/resources/application.yml`，默认指向本系列统一编排的 broker（host 可用环境变量 `RABBITMQ_HOST` 覆盖）。本地起一套的最快方式：

```bash
docker run -d --name sb4-rabbitmq -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=admin -e RABBITMQ_DEFAULT_PASS=admin123456 \
  rabbitmq:4-management
```

管理台 `http://localhost:15672`（admin / admin123456）。

## 二、快速开始

```bash
mvn spring-boot:run          # 或 mvn package 后 java -jar target/springboot4-rabbitmq-1.0.0.jar
```

创建订单（发布 ORDER_CREATED 事件，异步消费）：

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"product":"机械键盘","receiver":"熊大"}'
# → {"orderNo":"SO1001","product":"机械键盘","receiver":"熊大","status":"CREATED"}
```

支付（发布 ORDER_PAID）、看链路统计：

```bash
curl -X POST http://localhost:8080/api/orders/SO1001/pay
curl http://localhost:8080/api/notify/stats
```

演示重试与死信（给某个订单注入一次消费失败 / 发布毒丸事件）：

```bash
curl -X POST http://localhost:8080/api/notify/SO1001/inject-failure/1
curl -X POST http://localhost:8080/api/notify/SO1002/poison   # 3 次重试后进死信队列
```

## 三、拓扑与关键代码

```
RabbitTemplate.publish（routing key = order.created / order.paid）
      ▼
sb4.order.exchange (topic)  ── order.* ──▶  sb4.order.notify
                                             │ 消费失败 → 重试（Boot 4 底层是 Framework 7 RetryTemplate）
                                             │ 重试耗尽 → RejectAndDontRequeueRecoverer 拒收不回队
                                             ▼
                                    sb4.order.dlx (direct) ── order.dead ──▶ sb4.order.dead
```

- `config/RabbitTopologyConfig`：交换机/队列/绑定全 Bean 声明，业务队列挂 `x-dead-letter-exchange` 参数；`RabbitAdmin` 连接建立后统一声明（幂等）
- `config/RabbitClientConfig`：**唯一的 `MessageConverter` bean（Jackson 3 的 `JacksonJsonMessageConverter`）自动挂到 RabbitTemplate 与监听容器**；`RabbitTemplateCustomizer` 挂 confirm/return 回调
- `listener/OrderNotifyListener`：`@RabbitListener` 两个，业务队列 + 死信队列；参数直接是反序列化好的 `OrderEvent`
- `service/OrderService` / `NotificationService`：发布端与消费轨迹台账（内存版，真实工程落库）

## 四、Boot 4 关键事实（制品取证）

1. `spring-boot-starter-amqp` = starter + `spring-boot-amqp`（自带 spring-rabbit 4.1.1、amqp-client 5.30.0、spring-messaging）；自动配置包拆进 `org.springframework.boot.amqp.autoconfigure`
2. 属性前缀 `spring.rabbitmq.*` **没改名**
3. 消息转换器 Jackson 2/3 双轨：老 `Jackson2JsonMessageConverter`（com.fasterxml）还在，Boot 4 应该用新的 **`JacksonJsonMessageConverter`**（tools.jackson）
4. 重试底层从 Spring Retry 换成 **Framework 7 的 `org.springframework.core.retry.RetryTemplate`**；`max-attempts: 3` 实测语义是「初始 1 次 + 重试 3 次 = 共 4 次投递」
5. 测试用专属 starter `spring-boot-starter-amqp-test`（= starter-amqp + starter-test + spring-rabbit-test）

## 五、测试

```bash
mvn test        # 6 个用例全连真实 RabbitMQ，覆盖发布/confirm/重试/死信/404/409/400
```

注意测试前 broker 必须可达；测试用 `RabbitAdmin.purgeQueue` 清空两个队列后断言计数，别在消费端积压消息时跑。

## 六、常见坑（详见文章避坑指南）

- `@RabbitListener` 参数类型收不到对象、收到原始字节 → 容器里没有唯一的 `MessageConverter` bean，或容器工厂被自定义后没接转换器
- 重试次数和你想的不一样 → `max-attempts` 在 Boot 4 是「重试次数」不是「总投递次数」
- 毒丸消息无限重试 → 只开重试不配 DLX 时，异常最终走 requeue 循环；业务队列挂上死信参数才有兜底
- 消费重复 → 网络抖动下 ack 丢失会重投，消费端必须按 `eventId` 幂等

## License

MIT © Xiong Neng
