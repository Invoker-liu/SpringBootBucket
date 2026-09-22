# springboot4-starter：自己写一个 Spring Boot 4 Starter

Spring Boot 4.1.1 + JDK 21。本工程演示如何把自己的业务能力封装成可复用的 starter：
订单短消息通知（`order-notify-*`），双模块结构与官方 starter 完全同构。

## 工程结构

```text
springboot4-starter/
├── order-notify-spring-boot-autoconfigure   自动配置模块（代码 + 注册文件）
│   └── com.xncoding.starter.order.notify
│       ├── OrderNotifyProperties            属性类（order.notify.*）
│       ├── OrderNotifyAutoConfiguration     自动配置入口（条件装配）
│       ├── OrderNotifyService / DefaultOrderNotifyService
│       ├── SmsClient / LoggingSmsClient / NoopSmsClient
│       ├── MessageRenderer / JacksonMessageRenderer / PlainMessageRenderer
│       └── OrderNotifyCustomizer            customizer 收口点
│   └── META-INF/spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── order-notify-spring-boot-starter         空壳聚合模块（只有 pom，零代码）
└── order-notify-app                         演示应用（引用 starter）
```

## 构建顺序

在工程根目录（聚合 pom）一次完成，reactor 自动按依赖顺序构建：

```bash
mvn install
```

顺序是：`order-notify-spring-boot-autoconfigure`（先 install，starter 依赖它）
→ `order-notify-spring-boot-starter`（空壳，install 到本地仓库供外部引用）
→ `order-notify-app`（依赖前两者，起 Spring Boot 应用）。

单独重构建某个模块时按此顺序：先 autoconfigure，再 starter，app 最后。

## 运行

```bash
cd order-notify-app
SERVER_PORT=18170 mvn spring-boot:run
```

端口 18170。接口：

```bash
# 发一条订单通知（应用自带的打桩网关前两次必失败，可观测 starter 的重试）
curl -X POST http://localhost:18170/api/orders/SK-9001/notify

# 网关调用对账
curl http://localhost:18170/api/ops/sms-calls
```

## 测试

```bash
mvn test          # 或在根目录 mvn install，测试随构建执行
```

15 个用例，分两类：

- **autoconfigure 模块（10 个）**：`ApplicationContextRunner` 断言条件分支——
  默认全装配、`order.notify.enabled=false` 时整组 bean 不存在、用户提供
  `OrderNotifyService`/`SmsClient` 时默认实现让位、`channel=noop` 切换内置网关、
  classpath 缺 Jackson 时渲染器回退纯文本、属性松散绑定；imports 注册文件结构检查。
- **app 模块（5 个）**：MockMvc 打通知端点断言渲染内容（customizer 前缀 + JSON）；
  imports 反例——用自定义 classloader 滤掉自家 jar 的 imports 资源后起真实
  SpringApplication，启动零告警而 starter 的 bean 静默缺失。

## 实测结论

- starter 制品是空壳：官方 `spring-boot-starter-aspectj` 等解包只有
  LICENSE/NOTICE/MANIFEST（`Spring-Boot-Jar-Type: dependencies-starter`），
  代码与 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  都在 autoconfigure 模块。本工程结构照此复刻。
- 注册文件路径或文件名写错一个字符，启动零报错，自动配置静默缺失（有反例测试实证）。
- 应用日志 ERROR 0 行：网关失败走 WARN，重试 2 次后第 3 次成功，
  `NOTIFY_RETRY attempt=3/3` 全程可见。
- 同一次运行的取值单见 `.workbuddy/tmp/starter-summary.json`。
