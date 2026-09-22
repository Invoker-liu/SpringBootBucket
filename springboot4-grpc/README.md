# springboot4-grpc

Spring Boot 4.1.1 集成 gRPC 示例工程：物流服务用 gRPC 暴露「查询运单 / 通知发货 / 追踪轨迹」三个 RPC（proto3，两个 unary + 一个服务端流式），订单服务作为 gRPC 客户端经真实网络（localhost:18151）调用，HTTP 面保留订单与运单接口。

## 技术栈

- JDK 21、Maven 3.9
- Spring Boot 4.1.1：`spring-boot-starter-grpc-server` + `spring-boot-starter-grpc-client`（底层 spring-grpc 1.1.1、grpc-java 1.83.1）
- proto 代码生成：`io.github.ascopes:protobuf-maven-plugin:5.1.8`（版本由 Boot BOM 管理），protoc 4.35.1 + protoc-gen-grpc-java 1.83.1

## 运行

```bash
mvn spring-boot:run -f springboot4-grpc/pom.xml
```

- HTTP 接口：http://localhost:18150
- gRPC 服务：localhost:18151（`shipment.ShipmentService`）

构建说明：`mvn compile` 时 ascopes 插件自动从 Central 下载 protoc 与 grpc-java 插件的 Windows 二进制，把 `src/main/proto/shipment.proto` 生成到 `target/generated-sources/protobuf/`，生成代码（消息类 + `ShipmentServiceGrpc`）随后进入编译，无需手工 step。

## 接口清单

| 方法 | 路径 | 说明 | 内部 gRPC 调用 |
|---|---|---|---|
| POST | /api/orders | 下单，通知物流发货 | notifyShipped（unary，deadline 500ms） |
| GET | /api/orders/{orderNo} | 订单详情，刷新运单状态 | getShipment（unary，deadline 500ms） |
| GET | /api/shipments/{id} | 查询运单 | getShipment（unary），不存在映射 NOT_FOUND -> 404 |
| POST | /api/shipments/{id}/dispatch | 发货探针，拉取完整轨迹 | trackShipment（服务端流式，5 个事件） |
| POST | /api/fault | 故障注入 {"mode":"slow"} | 服务端 RPC 拖 1.2s 触发客户端 deadline |
| GET | /api/metrics | 调用计数台账 | 取值单数据源 |

## 测试

```bash
mvn test -f springboot4-grpc/pom.xml
```

5 个测试：`ShipmentGrpcInProcessTest` 3 个（`@AutoConfigureTestGrpcTransport` 进程内传输，round trip / 流式 5 事件 / NOT_FOUND 映射）+ `OrderServiceDegradeTest` 2 个（降级编排 Mockito 单测）。

端到端验证：

```bash
bash .workbuddy/verify-grpc.sh
```

## 实测结论（一轮 8 场景）

- 正常下单：HTTP 279 ms（含通道首次建连），服务端侧 NotifyShipped 8 ms
- 订单详情（gRPC 查运单）：14 ms；第二单 7 ms
- 服务端流式：dispatch 333 ms 收齐 5 个轨迹事件（服务端侧 320 ms）
- NOT_FOUND 映射：不存在的运单 HTTP 404
- deadline 降级：服务端拖 1.2 s，客户端 500 ms deadline 触发 DEADLINE_EXCEEDED，订单打 `DEGRADE_DEADLINE` 标记继续走完，HTTP 520 ms
- 计数对账：4 单 = 3 成功 + 1 降级；notifyCalls 4 = notifyOk 3 + notifyDegrades 1；deadlineExceeded 1；getCalls 3 = 2 正常 + 1 NOT_FOUND；trackCalls 1、streamEvents 5
- 应用日志 ERROR 0 行；GRPC_CALL 7 行 = 4 NotifyShipped + 2 GetShipment + 1 TrackShipment（NOT_FOUND 那次由异常拦截器直接关流，不经过业务调用日志）
