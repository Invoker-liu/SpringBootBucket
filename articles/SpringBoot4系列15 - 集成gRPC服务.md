---
title: SpringBoot4系列15 - 集成gRPC服务
slug: sb4-grpc
date: 2026-10-21 20:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, gRPC, RPC ]
draft: false
---

先交代一个我亲身踩过的坑：订单服务每次调用物流服务，双方都在赌对方没改接口。HTTP 面上跑的是 JSON：请求字段靠字符串拼，响应字段靠手抄文档。物流团队把响应里的 `waybillNo` 改名成 `shipmentId`，旧字段悄悄删掉，订单服务的解析代码拿到 `null` 照常往下走，三天后客服才发现运单号全空。改字段这种事在强类型世界有编译器拦着，在 JSON 世界只有运行时的 `null` 和日志里一条没人看的 WARN。那次之后我就认准了一件事：跨服务的字段契约不能靠人肉维护。

![](https://static.xiongneng.me/grpc-http-vs-contract-20260922030913.png)

gRPC 把契约钉死在 proto 文件里：字段名、类型、序号、方法签名全部进 schema，两端从同一份文件生成代码，改名字直接编译报错。这篇文章记录我用 Spring Boot 4.1.1 的官方 gRPC starter 落地这套方案的全过程：物流服务用 gRPC 暴露查询运单、通知发货两个 unary 接口加一个服务端流式的轨迹接口，订单服务作为客户端经真实网络调用，业务异常映射成 Status，deadline 实配并实测触发。文里的数字全部来自同一次端到端运行，原始日志我都留了底。

## 集成方案与自动配置的事实

Spring Boot 4.1 起官方原生集成 gRPC，依赖来自 spring-grpc 项目（`org.springframework.grpc`），自动配置由 Boot 自己的模块承载。写代码之前我习惯先把坐标与类位置钉死，免得写到一半发现引的是旧坐标。以下全部来自 Central 制品解包与 BOM 实测。

`spring-boot-dependencies:4.1.1` 管理的版本清单：

```text
spring-grpc            1.1.1     （org.springframework.grpc:spring-grpc-core）
grpc-java              1.83.1    （io.grpc:grpc-bom）
protobuf-java          4.35.1
protobuf-maven-plugin  5.1.8     （io.github.ascopes，pluginManagement 已管版本）
```

三个 starter 的组成（POM 实测）：

```text
spring-boot-starter-grpc-server = spring-boot-starter + spring-boot-grpc-server
                                  + io.grpc:grpc-netty + io.grpc:grpc-services
spring-boot-grpc-server         = spring-boot + spring-grpc-core
spring-boot-starter-grpc-client = spring-boot-starter + spring-boot-grpc-client
                                  + io.grpc:grpc-netty + io.grpc:grpc-stub
```

服务端 starter 自带 grpc-services（Health 与 Reflection 服务），客户端 starter 自带 grpc-stub。网络传输默认 Netty，与 HTTP 端口完全分离。

自动配置清单（`AutoConfiguration.imports` 实测），server 模块 7 条：

```text
org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthSchedulerAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerSecurityAutoConfiguration
org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerOAuth2ResourceServerAutoConfiguration
```

7 条我逐个过了一遍，对本篇起作用的是三条。`GrpcServerAutoConfiguration` 装配定制器集合与全局异常拦截器（下文 `@GrpcAdvice` 靠它生效）；`GrpcServerServicesAutoConfiguration` 把容器里全部 `BindableService` bean 绑定到服务器，反射服务也在这里挂上；`GrpcServerHealthAutoConfiguration` 在工程有至少一个 gRPC 服务时默认开启健康检查。客户端模块 3 条，核心是 `GrpcClientAutoConfiguration`：装配 `GrpcChannelFactory`（Netty 实现），供创建命名通道。

属性前缀这件事我还专门去字节码里验了一手：`GrpcServerProperties` 绑定 `spring.grpc.server`（35 条属性），`GrpcClientProperties` 绑定 `spring.grpc.client`。前缀带着 `spring.` 开头，属性元数据里也没有任何 `grpc.server.*` 裸前缀的条目。这个细节后面避坑指南里还会再提，配置写错前缀是没有任何报错提示的。

![](https://static.xiongneng.me/grpc-starter-map-20260922030926.png)

## 依赖和配置

工程三块依赖我一次配齐：`starter-webmvc` 支撑订单服务的 HTTP 面，`starter-grpc-server` 与 `starter-grpc-client` 各管一头：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-grpc-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-grpc-client</artifactId>
</dependency>
```

测试侧两个 starter：webmvc-test 管 HTTP 切片，grpc-server-test 自带进程内传输设施（测试一节展开）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-grpc-server-test</artifactId>
    <scope>test</scope>
</dependency>
```

proto3 代码生成用 BOM 管理的 ascopes 插件，版本号不用写。插件 v5 的配置模型用 `kind` 属性声明来源，与旧版写法完全不同：

```xml
<plugin>
    <groupId>io.github.ascopes</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <configuration>
        <protoc kind="binary-maven">
            <version>${protobuf-java.version}</version>
        </protoc>
        <plugins>
            <plugin kind="binary-maven">
                <groupId>io.grpc</groupId>
                <artifactId>protoc-gen-grpc-java</artifactId>
                <version>${grpc-java.version}</version>
            </plugin>
        </plugins>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

两个 `${...}` 属性都继承自 Boot parent，protoc 与 grpc 插件的 Windows 二进制构建时自动从 Central 下载，我本机 `mvn compile` 实测生成成功。生成物落在 `target/generated-sources/protobuf/`，Maven 自动加入编译源目录。插件版本千万别手写，写死一个与 BOM 不一致的号，九成会在 protoc 二进制下载那一步卡住。

proto 文件放在 `src/main/proto/shipment.proto`，`java_package` 指向独立包避免与手写代码混淆：

```protobuf
syntax = "proto3";

option java_multiple_files = true;
option java_package = "com.xncoding.grpc.shipment";
option java_outer_classname = "ShipmentProto";

package shipment;

service ShipmentService {
  rpc GetShipment(GetShipmentRequest) returns (ShipmentReply);
  rpc NotifyShipped(NotifyShippedRequest) returns (ShipmentReply);
  rpc TrackShipment(TrackShipmentRequest) returns (stream TrackEvent);
}
```

`TrackShipment` 的 `returns (stream TrackEvent)` 是服务端流式：一次请求，服务端逐条推送。生成代码的类名有命名规则要留意：消息类直接取 message 名，RPC 相关类挂在 `ShipmentServiceGrpc` 下，阻塞桩叫 `ShipmentServiceBlockingStub`、实现基类叫 `ShipmentServiceImplBase`，全部以 proto 里的 service 名为前缀。

配置文件里 server 与 client 两段，client 的通道按名字分组：

```yaml
server:
  port: ${SERVER_PORT:18150}

spring:
  grpc:
    server:
      port: ${GRPC_SERVER_PORT:18151}
      reflection:
        enabled: true
    client:
      channel:
        logistics:
          target: localhost:${GRPC_SERVER_PORT:18151}
```

`spring.grpc.server.port` 是 gRPC 自己的监听端口，与 Tomcat 的 18150 无关。客户端 `channel` 是一个 Map：键 `logistics` 是通道名，`target` 是这个通道连的地址，按 `host:port` 字面解析。代码里 `createChannel("logistics")` 就按这个名字取地址。

## 服务端：@GrpcService、拦截器与异常映射

服务实现继承生成代码的基类，标上 `@GrpcService` 注解。物流服务的内存运单存储加三个 RPC 实现：

```java
@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {

    private final Map<String, Shipment> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);
    private final FaultControl fault;

    public ShipmentGrpcService(FaultControl fault) {
        this.fault = fault;
    }

    @Override
    public void notifyShipped(NotifyShippedRequest request,
                              StreamObserver<ShipmentReply> responseObserver) {
        if (fault.isSlow()) {
            sleep(1200);
        }
        String id = "SHP" + seq.incrementAndGet();
        store.put(id, new Shipment(id, request.getOrderNo(), request.getCarrier(), "DISPATCHED"));
        Shipment saved = store.get(id);
        responseObserver.onNext(ShipmentReply.newBuilder()
                .setShipmentId(saved.id())
                .setOrderNo(saved.orderNo())
                .setCarrier(saved.carrier())
                .setStatus(saved.status())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getShipment(GetShipmentRequest request,
                            StreamObserver<ShipmentReply> responseObserver) {
        Shipment saved = store.get(request.getShipmentId());
        if (saved == null) {
            throw new ShipmentNotFoundException(request.getShipmentId());
        }
        responseObserver.onNext(toReply(saved));
        responseObserver.onCompleted();
    }
}
```

unary 方法拿到请求对象后填充响应、调 `onNext` 推给客户端、`onCompleted` 收尾。查不到运单我直接抛业务异常 `ShipmentNotFoundException`，异常怎么变成 gRPC 的 Status 由 advice 层负责，服务方法里没有一行 try-catch。这一点是我对这套集成最满意的地方：业务代码里干干净净，错误处理全部外置。

`@GrpcService` 注解在 `org.springframework.grpc.server.service` 包，携带 `interceptors` 与 `interceptorNames` 属性做服务级拦截器。全局拦截器另有一条路：实现 `ServerInterceptor` 后加 `@GlobalServerInterceptor`。日志拦截器记录每个 RPC 的方法名、终态与耗时，计时我放在 `close` 钩子上，能同时覆盖正常完成与异常关流两种结局：

```java
@Component
@GlobalServerInterceptor
public class GrpcCallLogInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcCallLogInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        long t0 = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();
        ServerCall<ReqT, RespT> wrapped = new ForwardingServerCall
                .SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.info("GRPC_CALL method={} code={} elapsedMs={}",
                        method, status.getCode(), ms);
                super.close(status, trailers);
            }
        };
        return new ForwardingServerCallListener
                .SimpleForwardingServerCallListener<>(next.startCall(wrapped, headers)) {
        };
    }
}
```

实测一轮八个场景后的拦截器日志（7 行，同一次运行）：

```text
GRPC_CALL method=shipment.ShipmentService/NotifyShipped code=OK elapsedMs=13
GRPC_CALL method=shipment.ShipmentService/GetShipment   code=OK elapsedMs=0
GRPC_CALL method=shipment.ShipmentService/NotifyShipped code=OK elapsedMs=0
GRPC_CALL method=shipment.ShipmentService/GetShipment   code=OK elapsedMs=0
GRPC_CALL method=shipment.ShipmentService/TrackShipment code=OK elapsedMs=313
GRPC_CALL method=shipment.ShipmentService/NotifyShipped code=OK elapsedMs=0
GRPC_CALL method=shipment.ShipmentService/NotifyShipped code=OK elapsedMs=1210
```

首调 13 ms 里有通道建连开销，复用之后降到 0 ms 量级；流式的 TrackShipment 313 ms 推完 5 个事件。`ForwardingServerCall` 包装的 `close` 把每个 RPC 的终态都留了痕，排查线上问题时这 7 行比堆栈有用。

业务异常映射用 `@GrpcAdvice` 组件类，方法级 `@GrpcExceptionHandler` 按异常类型注册，返回 `StatusException`：

```java
@GrpcAdvice
public class GrpcExceptionAdvice {

    @GrpcExceptionHandler(ShipmentNotFoundException.class)
    public StatusException handleNotFound(ShipmentNotFoundException e) {
        Metadata trailers = new Metadata();
        Metadata.Key<String> key =
                Metadata.Key.of("shipment-id", Metadata.ASCII_STRING_MARSHALLER);
        trailers.put(key, e.getShipmentId());
        return Status.NOT_FOUND.withDescription(e.getMessage()).asException(trailers);
    }
}
```

两个注解都在 `org.springframework.grpc.server.advice` 包。Boot 的自动配置会把容器里的 advice 收进一个全局异常拦截器，业务方法保持干净，客户端拿到的就是标准的 `NOT_FOUND`。我实测 `GET /api/shipments/SHP-NOPE`：客户端收到 `StatusRuntimeException` 后映射成 HTTP 404，trailer 里带着出问题的运单号。

![](https://static.xiongneng.me/grpc-call-flow-20260922031011.png)

## 客户端：命名通道、deadline 与降级

spring-grpc 1.1 移除了旧版的 `@GrpcClient` 注解，stub 装配走两条路：手写 bean，或者 `@ImportGrpcClients` 扫描注册。我选了手写 bean，通道名与配置文件里的 Map 键对应，看得见摸得着：

```java
@Configuration
public class GrpcClientConfig {

    @Bean
    ShipmentServiceGrpc.ShipmentServiceBlockingStub shipmentStub(GrpcChannelFactory channels) {
        return ShipmentServiceGrpc.newBlockingStub(channels.createChannel("logistics"));
    }
}
```

`GrpcChannelFactory`（`org.springframework.grpc.client` 包）由自动配置提供，`createChannel("logistics")` 按 `VirtualTargets` 规则去 `spring.grpc.client.channel.logistics.target` 取地址。生成的阻塞桩注入后就是普通 bean，与本地服务无差别。

调用侧的核心是 deadline 与 Status 分流。下单链路里的物流通知允许失败：deadline 到了就走降级，订单照常落库：

```java
public Optional<ShipmentView> notifyShipped(String orderNo, String carrier, int itemCount) {
    metrics.incNotifyCalls();
    long t0 = System.nanoTime();
    try {
        ShipmentReply reply = stub.withDeadlineAfter(NOTIFY_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .notifyShipped(NotifyShippedRequest.newBuilder()
                        .setOrderNo(orderNo)
                        .setCarrier(carrier)
                        .setItemCount(itemCount)
                        .build());
        long ms = elapsed(t0);
        metrics.recordNotifyLatency(ms);
        metrics.incNotifyOk();
        return Optional.of(ShipmentView.of(reply));
    } catch (StatusRuntimeException e) {
        long ms = elapsed(t0);
        metrics.recordNotifyLatency(ms);
        metrics.incNotifyDegrades();
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
            metrics.incDeadlineExceeded();
        }
        log.warn("GRPC_DEGRADE op=notifyShipped code={} elapsedMs={}",
                e.getStatus().getCode(), ms);
        return Optional.empty();
    }
}
```

`withDeadlineAfter` 每次调用都要挂：stub 是无状态的可复用对象，`withXxx` 返回新实例，原桩不受影响。unary 的 deadline 我配了 500 ms，超时抛 `StatusRuntimeException`，`getCode()` 区分是超时还是服务端报错。`getShipment` 的分流更细：`NOT_FOUND` 是业务结论不是故障，单独映射成空值交给 HTTP 层回 404，其余异常原样上抛。

deadline 降级的实测一轮（我用故障注入把服务端拖到 1.2 s）：

```text
GRPC_DEGRADE op=notifyShipped code=DEADLINE_EXCEEDED elapsedMs=512
GRPC_CALL    method=shipment.ShipmentService/NotifyShipped code=OK elapsedMs=1210
```

这两行日志里有本次集成最反直觉的一个结果，我单独拎出来说：客户端 512 ms 就放弃并给订单打上 `DEGRADE_DEADLINE` 标记照常完成，服务端却对自己被放弃这件事毫无感知，1.2 s 后照常执行完并把运单建了出来。deadline 只约束客户端的等待，不取消服务端的执行。我的判断是：慢操作占用资源的时长必须按服务端视角评估，客户端的超时数字给不了你任何保障，要做熔断就得让服务端也读 deadline。

![](https://static.xiongneng.me/grpc-deadline-timeline-20260922031015.png)

服务端流式的消费比想象中朴素，就是迭代器：

```java
public List<TrackEventView> trackShipment(String shipmentId) {
    metrics.incTrackCalls();
    Iterator<TrackEvent> events = stub.withDeadlineAfter(TRACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
            .trackShipment(TrackShipmentRequest.newBuilder()
                    .setShipmentId(shipmentId)
                    .build());
    List<TrackEventView> list = new ArrayList<>();
    events.forEachRemaining(e -> list.add(new TrackEventView(
            e.getStatus(), e.getLocation(), e.getOccurredAt())));
    metrics.addStreamEvents(list.size());
    return list;
}
```

阻塞桩的流式方法返回 `Iterator`，逐条拉取服务端推送的事件。deadline 覆盖整条流的生命周期：5 个事件每个间隔 60 ms，总耗时 313 ms，deadline 我给到 2 s 留出余量。流式调用比 unary 多一个失败维度，中途断流会在迭代时抛异常，收集完的条数要留进计数器。

## 完整案例：一轮八个场景的原始记录

端到端验证我起了真实应用：HTTP 在 18150，gRPC 在 18151，订单接口内部经真实网络 gRPC 调用物流服务。八个场景一轮跑完，HTTP 状态码全部符合预期，应用日志 ERROR 0 行。

场景一是正常下单，`POST /api/orders` 请求体 `{"orderNo":"SK-9001","amount":"299.00","itemCount":3}`，内部走 `notifyShipped`：

```json
{"orderNo":"SK-9001","amount":299.00,"itemCount":3,
 "shipment":{"shipmentId":"SHP1001","orderNo":"SK-9001","carrier":"STO","status":"DISPATCHED"},
 "status":"OK","placedAtMs":1789844805679}
```

![](https://static.xiongneng.me/grpc-orders-panels-20260920030758.png)

场景二查订单详情，`GET /api/orders/SK-9001` 内部走 `getShipment` 刷新运单状态，返回 `DISPATCHED`。场景三第二单，`SHP1002` 正常生成。场景四做服务端流式验证，`POST /api/shipments/SHP1001/dispatch` 一次调用拉回完整轨迹，5 个事件按 `PICKED_UP` 到 `DELIVERED` 顺序推达，服务端侧耗时 313 ms。场景五做异常映射，`GET /api/shipments/SHP-NOPE` 客户端收到 `NOT_FOUND`，HTTP 层返回 404。

场景六做 deadline 降级。控制面把故障模式切成 slow，服务端 `notifyShipped` 拖 1.2 s，超过客户端 500 ms deadline：

```json
{"orderNo":"SK-9003","amount":55.00,"itemCount":2,
 "shipment":null,"status":"DEGRADE_DEADLINE","placedAtMs":1789844875275}
```

场景七恢复故障模式后复下一单，`SHP1004` 正常生成。场景八做计数对账，`GET /api/metrics` 输出全部计数器：

```json
{"ordersPlaced":4,"notifyCalls":4,"notifyOk":3,"notifyDegrades":1,
 "deadlineExceeded":1,"getCalls":3,"notFoundMapped":1,
 "trackCalls":1,"streamEvents":5,
 "avgNotifyLatencyMs":194,"lastNotifyLatencyMs":3}
```

这笔账我对了一遍：4 次下单 = 3 次物流通知成功 + 1 次 deadline 降级；3 次 `getShipment` = 2 次正常 + 1 次 NOT_FOUND 映射；流式 1 次调用 5 个事件。平均通知延迟 194 ms 被降级那次的 512 ms 等待拉高，恢复后末次延迟 3 ms 是链路回到健康水位的铁证。日志侧 `GRPC_DEGRADE` 1 行、`ORDER_PLACED` 4 行，与场景数一致。

![](https://static.xiongneng.me/grpc-metrics-panels-20260920030758.png)

## 测试怎么写

gRPC 面的测试用 Boot 自带的进程内传输，不占端口也不起网络栈，说实话这是我本次集成里最喜欢的一块设计。`@AutoConfigureTestGrpcTransport` 把服务端工厂与客户端通道工厂全换成 in-process 实现，`@SpringBootTest` 起来之后 stub bean 照常注入、照常调用：

```java
@SpringBootTest
@AutoConfigureTestGrpcTransport
class ShipmentGrpcInProcessTest {

    @Autowired
    ShipmentServiceGrpc.ShipmentServiceBlockingStub stub;

    @Test
    void notifyThenGet_roundTrip() {
        ShipmentReply created = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .notifyShipped(NotifyShippedRequest.newBuilder()
                        .setOrderNo("SK-T001").setCarrier("STO").setItemCount(2).build());
        assertThat(created.getShipmentId()).startsWith("SHP");
        assertThat(created.getStatus()).isEqualTo("DISPATCHED");

        ShipmentReply fetched = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getShipment(GetShipmentRequest.newBuilder()
                        .setShipmentId(created.getShipmentId()).build());
        assertThat(fetched.getOrderNo()).isEqualTo("SK-T001");
    }

    @Test
    void unknownShipment_adviceMapsToNotFound() {
        assertThatThrownBy(() -> stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getShipment(GetShipmentRequest.newBuilder()
                        .setShipmentId("SHP-NOPE").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e ->
                        assertThat(e.getStatus().getCode())
                                .isEqualTo(Status.Code.NOT_FOUND));
    }
}
```

测试里注入的 stub 与生产是同一个 bean：通道工厂被测试自动配置替换成进程内实现，通道名、stub 类型、业务代码全部不变。第二个用例顺带验证了 advice 的映射在进程内同样生效，异常类型与 Status 码都在断言里。流式的用例迭代计数断言 5 个事件、末态 `DELIVERED`。

降级编排用 Mockito 直接测订单服务，不起 gRPC 上下文：

```java
@Test
void notifyFails_orderStillPlacedWithDegradeMarker() {
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.notifyShipped(anyString(), anyString(), anyInt()))
            .thenReturn(Optional.empty());
    OrderService service = new OrderService(logistics, new CallMetrics());

    OrderService.Order order = service.placeOrder("SK-T003", new BigDecimal("42.00"), 1);

    assertThat(order.status()).isEqualTo("DEGRADE_DEADLINE");
    assertThat(order.shipment()).isNull();
}
```

客户端把异常收敛成 `Optional.empty()` 之后，订单服务的降级逻辑就有了稳定的输入契约：mock 返回空值，断言订单照常落库且标记正确。3 个进程内切片测试加 2 个降级单测，5 个测试全绿，整轮测试 4 秒。

测试依赖有一个坑位要交待：gRPC 的测试 starter 拆成 server 与 client 两个（`spring-boot-starter-grpc-server-test` / `spring-boot-starter-grpc-client-test`），`@AutoConfigureTestGrpcTransport` 注解与进程内工厂都在 `spring-boot-grpc-test` 模块里，两个测试 starter 都会带上它。4.1.1 里没有名为 `spring-boot-starter-grpc-test` 的统一制品。

## 避坑指南

**坑一，两个 starter 各管一头，别想着一个顶两个。** `spring-boot-starter-grpc-server` 与 `spring-boot-starter-grpc-client` 是独立制品，服务端不带来 grpc-stub，客户端不带来 grpc-services。同工程既做服务端又做客户端就两个都引，缺哪个哪个的类找不到。

**坑二，属性前缀是 `spring.grpc.*`，不是 `grpc.*`。** 字节码里 `@ConfigurationProperties` 的值是 `spring.grpc.server` 与 `spring.grpc.client`，配置写在 `grpc.server.port` 下会静默不绑定，端口回落默认值 9090，排查起来毫无提示，容易闹笑话。

**坑三，`@GrpcClient` 注解已经不存在。** spring-grpc 0.x 的注解在 1.x 里被移除，Central 上 1.1.1 的 jar 里搜不到这个类。stub 装配走手写 bean（注入 `GrpcChannelFactory`）或 `@ImportGrpcClients`，网上照抄旧注解的代码编译不过。

**坑四，通道 target 按 `host:port` 字面解析。** Netty 通道工厂不支持带 scheme 的地址，`static://localhost:18151` 这类写法会在建连时被拒。要按名寻址就用 `spring.grpc.client.channel.<名>.target`，进程内寻址用 `in-process:` 前缀。

**坑五，`@GlobalServerInterceptor` 不带组件语义。** 它是纯标记注解，类上不补 `@Component` 拦截器就进不了容器，所有 RPC 照常工作、日志一行不打。全局拦截器也可以改用 `@Bean` 方法声明，`@Order` 控制顺序。

**坑六，生成类名以 service 名为前缀。** `ShipmentService` 生成的阻塞桩是 `ShipmentServiceBlockingStub`，想当然写成 `ShipmentBlockingStub` 编译直接报错。`java_multiple_files = true` 只拆消息类，RPC 桩与基类仍挂在 `ShipmentServiceGrpc` 外壳类下。

**坑七，deadline 不取消服务端执行。** 客户端超时放弃后，服务端方法照常跑完（实测客户端 512 ms 放弃、服务端 1210 ms 完成）。用 deadline 做熔断时要评估慢操作在服务端堆积的后果，必要时在服务端读 deadline 并主动中断。

**坑八，advice 拦截器在调用链外层接异常。** 业务异常被 `@GrpcAdvice` 转成 Status 后直接在原始 call 上关流，位置更靠内的拦截器收不到这次调用的 `close` 通知。按日志对账调用数时要把这一层算进去：实测 8 次 RPC 只有 7 行调用日志，差的 1 次就是 NOT_FOUND 那单。

**坑九，ascopes 插件 v5 的配置模型换了。** BOM 管理的 5.1.8 用 `kind="binary-maven"` 属性声明 protoc 与插件来源，旧版 0.6.x 的 `<executions>` + `<configuration.protoFile>` 写法照搬过来不生效。版本号交给 BOM，只写坐标。

## 小结

选型建议：内部服务之间的调用，只要字段变更频繁或者调用方不止一个，proto 契约越早引入越省心。Boot 4.1 把引入成本压到了三个 starter 加一个插件配置，proto 生成代码进 `target` 不进版本库，契约变更的反馈从运行期的 `null` 提前到编译期报错；测试侧进程内传输不占端口不碰网络，gRPC 面的切片测试可以放心写进日常回归。服务端 `@GrpcService` 加基类实现、`@GrpcAdvice` 映射异常、客户端命名通道加 deadline 分流，这套骨架你可以直接照搬。

没解决的事有两件。一是 deadline 只约束客户端等待，服务端照常执行，我目前只能在拦截器里读 deadline 主动中断慢操作，怎么和业务降级策略统一编排还没想清楚。二是流式中途断流的断点续拉，要在 proto 里自己设计游标协议，这次没做，等真出现长轨迹场景我再补。

## 参考链接

- [Spring gRPC Reference](https://docs.spring.io/spring-grpc/reference/)：spring-grpc 官方文档，server / client 两章覆盖服务实现、stub 装配与拦截器模型
- [Spring Boot Reference - gRPC](https://docs.spring.io/spring-boot/reference/rpc/grpc.html)：Boot 4.1 的 gRPC 自动配置、starter 清单与 `spring.grpc.*` 属性说明
- [spring-grpc GitHub 仓库](https://github.com/spring-projects/spring-grpc)：源码与示例工程，1.1.x 起 spring-boot 自动配置模块收编进 Boot 官方 starter
- [gRPC Java 官方文档](https://grpc.io/docs/languages/java/)：grpc-java 的 deadline、Status 语义与流式 API 行为
- [protobuf-maven-plugin](https://github.com/ascopes/protobuf-maven-plugin)：ascopes 插件文档，v5 配置模型与 protoc 二进制解析规则
- [Protocol Buffers Language Guide (proto3)](https://protobuf.dev/programming-guides/proto3/)：proto3 语法与字段规则
- [spring-boot-starter-grpc-server 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-grpc-server/4.1.1/)：本文 starter 组成与版本矩阵的制品实证来源
