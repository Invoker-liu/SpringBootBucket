# SpringBoot 4.x 全家桶

当前项目是 [SpringBootBucket](https://github.com/yidao620c/SpringBootBucket) 的 Spring Boot 4.x 重制版。

对 Java Web 开发中常用的各项技术，通过和 Spring Boot 的集成，以「**一篇博客 + 一个可运行项目**」的形式详细说明。

每个子项目只引入完成该主题所需的最小依赖，可独立运行，也可按业务需要自由组合。

> 基于 Spring Boot 2.0 的旧版教程已归档至 `springboot2.0` 分支，保持只读不再维护。

## 版本基线

| 项 | 版本 |
| --- | --- |
| JDK | 21（Spring Boot 4 基线为 17，推荐 21 / 25） |
| Spring Boot | 4.1.1 |
| Spring Framework | 7.0.9 |
| Maven | 3.9 |
| 容器 | Tomcat 11（内置，Undertow 已在 4.0 移除） |
| JSON | Jackson 3（`tools.jackson` 包名） |

## 环境准备

依赖外部中间件的主题（数据库、缓存、消息队列）统一使用 Docker Compose 编排，无需在宿主机单独安装：

```bash
docker compose up -d          # 启动 MySQL / MongoDB / Redis / RabbitMQ
docker compose ps             # 查看容器与健康状态
docker compose down           # 停止
docker compose down -v        # 停止并清空数据
```

| 服务 | 端口 | 账号 |
| --- | --- | --- |
| MySQL 8.4 | 3306 | root / root123456 |
| MongoDB 8.0 | 27017 | root / root123456 |
| Redis 8.0 | 6379 | 密码 root123456 |
| RabbitMQ 4（含管理台） | 5672 / 15672 | admin / admin123456 |

各主题所需的建库建表脚本放在对应工程的 `src/main/resources/sql/` 下，具体步骤见该工程的 README。

## 运行方式

每个子项目均可独立运行：

```bash
cd springboot4-xxx
mvn clean package
java -jar target/springboot4-xxx-1.0.0.jar
```

也可以直接在 IDE 中运行启动类的 `main` 方法。默认端口为 `8080`，如与本地服务冲突，在 `application.yml` 中调整 `server.port`。

## 主题索引

进度以 `待编写 → 进行中 → 已完成` 标记。

### P-1 Web 与数据

| 项目 | 文章 | 状态 |
| --- | --- | --- |
| [springboot4-restful](springboot4-restful) | [SpringBoot4系列01 - 实现RESTful接口](articles/SpringBoot4系列01%20-%20实现RESTful接口.md) | 已完成 |
| [springboot4-mybatis](springboot4-mybatis) | [SpringBoot4系列02 - 集成MyBatis-Plus](articles/SpringBoot4系列02%20-%20集成MyBatis-Plus.md) | 已完成 |
| [springboot4-jpa](springboot4-jpa) | [SpringBoot4系列03 - 集成Spring Data JPA与Hibernate 7](articles/SpringBoot4系列03%20-%20集成Spring%20Data%20JPA与Hibernate%207.md) | 已完成 |
| [springboot4-mongodb](springboot4-mongodb) | [SpringBoot4系列04 - 集成MongoDB](articles/SpringBoot4系列04%20-%20集成MongoDB.md) | 已完成 |
| [springboot4-multisource](springboot4-multisource) | [SpringBoot4系列05 - 多数据源配置](articles/SpringBoot4系列05%20-%20多数据源配置.md) | 已完成 |
| [springboot4-transaction](springboot4-transaction) | [SpringBoot4系列06 - 声明式事务](articles/SpringBoot4系列06%20-%20声明式事务.md) | 已完成 |

### P-2 中间件与集成

| 项目 | 文章 | 状态 |
| --- | --- | --- |
| [springboot4-redis](springboot4-redis) | [SpringBoot4系列07 - Redis数据库](articles/SpringBoot4系列07%20-%20Redis数据库.md) | 已完成 |
| [springboot4-cache](springboot4-cache) | [SpringBoot4系列08 - 使用缓存](articles/SpringBoot4系列08%20-%20使用缓存.md) | 已完成 |
| [springboot4-rabbitmq](springboot4-rabbitmq) | [SpringBoot4系列09 - 使用消息队列RabbitMQ](articles/SpringBoot4系列09%20-%20使用消息队列RabbitMQ.md) | 已完成 |
| [springboot4-batch](springboot4-batch) | [SpringBoot4系列10 - 使用批处理Spring Batch](articles/SpringBoot4系列10%20-%20使用批处理Spring%20Batch.md) | 已完成 |
| [springboot4-schedule](springboot4-schedule) | [SpringBoot4系列11 - 使用定时任务Schedule](articles/SpringBoot4系列11%20-%20使用定时任务Schedule.md) | 已完成 |
| [springboot4-async](springboot4-async) | [SpringBoot4系列12 - 使用异步任务与线程池](articles/SpringBoot4系列12%20-%20使用异步任务与线程池.md) | 已完成 |
| [springboot4-websocket](springboot4-websocket) | [SpringBoot4系列13 - 使用WebSocket实时通信](articles/SpringBoot4系列13%20-%20使用WebSocket实时通信.md) | 已完成 |
| [springboot4-restclient](springboot4-restclient) | [SpringBoot4系列14 - 使用声明式HTTP客户端RestClient](articles/SpringBoot4系列14%20-%20使用声明式HTTP客户端RestClient.md) | 已完成 |
| [springboot4-grpc](springboot4-grpc) | [SpringBoot4系列15 - 集成gRPC服务](articles/SpringBoot4系列15%20-%20集成gRPC服务.md) | 已完成 |

### P-3 工程化与安全

| 项目 | 文章 | 状态 |
| --- | --- | --- |
| [springboot4-aop](springboot4-aop) | [SpringBoot4系列16 - 使用AOP](articles/SpringBoot4系列16%20-%20使用AOP.md) | 已完成 |
| [springboot4-starter](springboot4-starter) | [SpringBoot4系列17 - 自己写Starter](articles/SpringBoot4系列17%20-%20自己写Starter.md) | 已完成 |
| [springboot4-security](springboot4-security) | [SpringBoot4系列18 - 使用Spring Security 7权限管理](articles/SpringBoot4系列18%20-%20使用Spring%20Security%207权限管理.md) | 已完成 |
| [springboot4-oauth2](springboot4-oauth2) | [SpringBoot4系列19 - 使用OAuth2与JWT接口认证](articles/SpringBoot4系列19%20-%20使用OAuth2与JWT接口认证.md) | 已完成 |
| [springboot4-openapi](springboot4-openapi) | [SpringBoot4系列20 - 使用OpenAPI 3.1接口文档](articles/SpringBoot4系列20%20-%20使用OpenAPI%203.1接口文档.md) | 已完成 |
| [springboot4-testing](springboot4-testing) | [SpringBoot4系列21 - 测试体系JUnit 6与Testcontainers](articles/SpringBoot4系列21%20-%20测试体系JUnit%206与Testcontainers.md) | 已完成 |
| [springboot4-apiversion](springboot4-apiversion) | [SpringBoot4系列22 - 使用API版本管理](articles/SpringBoot4系列22%20-%20使用API版本管理.md) | 已完成 |
| [springboot4-jackson3](springboot4-jackson3) | [SpringBoot4系列23 - 使用Jackson 3与空安全](articles/SpringBoot4系列23%20-%20使用Jackson%203与空安全.md) | 已完成 |

### P-4 生产化

| 项目 | 文章 | 状态 |
| --- | --- | --- |
| [springboot4-observability](springboot4-observability) | [SpringBoot4系列24 - 使用Actuator与OpenTelemetry可观测性](articles/SpringBoot4系列24%20-%20使用Actuator与OpenTelemetry可观测性.md) | 已完成 |
| [springboot4-resilience](springboot4-resilience) | [SpringBoot4系列25 - 使用内置弹性重试与并发限制](articles/SpringBoot4系列25%20-%20使用内置弹性重试与并发限制.md) | 已完成 |
| [springboot4-native](springboot4-native) | [SpringBoot4系列26 - 使用GraalVM原生镜像与AOT](articles/SpringBoot4系列26%20-%20使用GraalVM原生镜像与AOT.md) | 已完成 |
| [springboot4-virtualthreads](springboot4-virtualthreads) | [SpringBoot4系列27 - 使用虚拟线程提升吞吐](articles/SpringBoot4系列27%20-%20使用虚拟线程提升吞吐.md) | 已完成 |
| [springboot4-thymeleaf](springboot4-thymeleaf) | [SpringBoot4系列28 - 集成Thymeleaf服务端渲染](articles/SpringBoot4系列28%20-%20集成Thymeleaf服务端渲染.md) | 已完成 |
| [springboot4-echarts](springboot4-echarts) | [SpringBoot4系列29 - 服务端图表数据接口与PNG导出](articles/SpringBoot4系列29%20-%20服务端图表数据接口与PNG导出.md) | 已完成 |

### P-5 前沿

| 项目 | 文章 | 状态 |
| --- | --- | --- |
| [springboot4-ai-mcp](springboot4-ai-mcp) | [SpringBoot4系列30 - 用Spring AI把业务接口暴露成MCP工具](articles/SpringBoot4系列30%20-%20用Spring%20AI把业务接口暴露成MCP工具.md) | 已完成 |

## 目录结构

```
.
├── articles/                 # 教程文章（Markdown）
├── springboot4-xxx/          # 各主题可运行工程
├── docker-compose.yml        # 本地中间件编排
```

## 许可证

Copyright (c) 2018-2026 [Xiong Neng](https://www.xncoding.me/)

基于 MIT 协议发布：<http://www.opensource.org/licenses/MIT>
