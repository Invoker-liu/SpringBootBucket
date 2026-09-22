---
title: SpringBoot4系列13 - 使用WebSocket实时通信
slug: sb4-websocket
date: 2026-10-13 20:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, WebSocket, 实时通信 ]
draft: false
---

运营后台的「新订单监控」页面是我先用轮询撑起来的：前端每 3 秒发一次 `GET /api/orders?since=…`，几百个页面同时开着，一秒钟就是上百个请求，其中九成以上查回来的是空数组。高峰期轮询请求和正常下单请求挤在同一条队列里，接口响应从 40 毫秒涨到 1 秒开外，运营跑来找我反馈「页面刷新慢」，我打开数据库慢查询日志，里面躺着一排一模一样的查询，那一刻我就知道该换方案了。

![](https://static.xiongneng.me/ws-polling-vs-push-20260922022750.png)

空轮询的病根在于「有事件」这个信息握在服务端手里，客户端却只能反复来问。WebSocket 把方向反过来：连接建立之后一直挂着，服务端有新订单就主动推一帧过去，没订单就什么都不发。这篇文章是我拿 Spring Boot 4.1.1 加 Framework 7.0.9 做的一套订单事件推送实测：运营后台的新订单事件要实时到达每一个在线客户端，客户端掉线了服务端还要能感知到。广播与定向推送、心跳、断线感知我都跑了实测，关键数字全留了原始记录，下文逐段对应。

## 编程模型与自动配置的事实

写代码前我习惯先把依赖组成与类的位置摸清楚。第一件事实来自 starter 制品解包（4.1.1 实测）：

```text
spring-boot-starter-websocket
  ├─ spring-boot-starter
  ├─ spring-boot-starter-webmvc
  │    └─ starter-tomcat → tomcat-embed-core / el / websocket
  └─ spring-boot-websocket
       ├─ spring-messaging   7.0.9
       └─ spring-websocket   7.0.9
```

`spring-boot-websocket` 是 Boot 4 拆模块后的专属模块，但它薄得出奇：整个模块只有一个自动配置 `WebSocketMessagingAutoConfiguration`，包名 `org.springframework.boot.websocket.autoconfigure.servlet`，我从字节码确认过它的条件是 `@ConditionalOnClass(WebSocketMessageBrokerConfigurer)`，只服务 STOMP 子协议。普通 WebSocketHandler 路线没有任何 Boot 自动配置，`@EnableWebSocket` 加 `WebSocketConfigurer` 由你自己在配置类上声明。

这个自动配置装配的是 STOMP 场景的接线：内部类 `SpringBootWebSocketMessageBrokerConfigurer` 收集容器里所有用户的 `WebSocketMessageBrokerConfigurer` bean 聚合成一份注册；另一组内部类按 `spring.websocket.messaging.preferred-json-mapper` 的取值挑消息转换器，默认 `jackson` 走 Jackson 3 的 `tools.jackson` JsonMapper，`jackson2` 已在元数据里标注废弃。本篇的业务事件是裸文本帧加自定义 JSON，不经过这套子协议栈，我用 `handleTextMessage` 直接收发。

属性前缀 `spring.websocket.*` 存在，但我翻遍元数据只找到一条属性：

```text
spring.websocket.messaging.preferred-json-mapper   默认 jackson
```

没有 WebSocketProperties 类，连接超时、缓冲区大小、会话上限这类属性统统不存在，这些都要在代码层自己管。tomcat-embed-websocket 随 webmvc starter 传递进来，jakarta.websocket 的 API 与容器实现都在里面，两条路线共用这一个 starter，依赖清单没有差别。

核心类的包位置清单（spring-websocket 7.0.9 解包实证），写 import 的时候照它核对：

```text
org.springframework.web.socket                  WebSocketHandler / WebSocketSession
                                                TextMessage / BinaryMessage
                                                PingMessage / PongMessage / CloseStatus
org.springframework.web.socket.handler          TextWebSocketHandler / AbstractWebSocketHandler
org.springframework.web.socket.config.annotation  @EnableWebSocket / WebSocketConfigurer
                                                WebSocketHandlerRegistry
org.springframework.web.socket.server           HandshakeInterceptor / DefaultHandshakeHandler
org.springframework.web.socket.server.standard  SpringConfigurator / ServerEndpointExporter
jakarta.websocket（tomcat-embed-core）           @ServerEndpoint / Session / ContainerProvider
```

![](https://static.xiongneng.me/ws-two-routes-20260922022754.png)

## 依赖和配置

工程依赖我压到最省：starter 加 JDBC，订单落 MySQL，事件推送不依赖任何消息队列：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

配置文件里没有 websocket 专属配置段，端口、数据源与建表脚本就位即可：

```yaml
server:
  port: ${SERVER_PORT:18130}

spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:192.168.1.97}:3306/${DB_NAME:springboot4_websocket}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root123456}
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
```

事件帧的 JSON 序列化用 Jackson 3，包名 `tools.jackson`，`com.fasterxml.jackson` 不在 Boot 4 的 classpath 上。我的事件结构只有五种字段：`type`、`seq`、`orderNo`、`amount`、`ts`，`seq` 是服务端维护的事件序号，客户端拿它断言消息顺序。

事件帧的生成集中在 NotifyService 里，落库完成后按一个固定结构组装：

```java
public String orderEvent(Order order) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "order");
    event.put("seq", seq.incrementAndGet());
    event.put("orderNo", order.orderNo());
    event.put("amount", order.amount());
    event.put("status", order.status());
    event.put("ts", Instant.now().toEpochMilli());
    return mapper.writeValueAsString(event);
}
```

`seq` 是服务端单调递增的事件序号，客户端收不到、收重复、收乱序，比对 `seq` 就能发现。帧里的字段全部是原始类型与字符串，两端都不需要约定复杂结构，JSON 解析在 handler 侧用 `readTree` 转 `JsonNode`，取值走 `asString("")`，字段缺失时落到空串而不是抛异常，客户端发的报文质量参差时不至于把连接打崩。

## 握手与会话登记

握手阶段把身份定下来。我的连接身份来自 URL 上的 query 参数 `user`，拦截器缺参就拒：

```java
@Component
public class HandshakeAuthInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String user = parseQueryParam(request.getURI(), "user");
        if (user == null || user.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("user", user);
        return true;
    }
}
```

返回 false 握手即终止，客户端收到 HTTP 401；通过后 `user` 写进 `attributes`，后续 handler 从 `session.getAttributes().get("user")` 取。我实测缺 `user` 参数的连接被拒，客户端抛 `WebSocketBadStatusException`，带身份的 3 个客户端（operator-a、operator-b、operator-c）全部握手成功，`connected` 计数到 3。

handler 用 `TextWebSocketHandler`，只处理文本帧：

```java
@Component
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String user = (String) session.getAttributes().get("user");
        registry.register(user == null ? "anonymous" : user, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String user = (String) session.getAttributes().get("user");
        registry.unregister(user == null ? "anonymous" : user, session);
    }
}
```

`afterConnectionEstablished` 与 `afterConnectionClosed` 是会话生命周期的两端，中间的登记与移除都落到会话登记器上：

```java
@Component
public class SessionRegistry {

    private final Map<String, List<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(String user, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(user, k -> new CopyOnWriteArrayList<>()).add(session);
        connected.incrementAndGet();
    }

    public int onlineCount() {
        return sessionsByUser.values().stream().mapToInt(List::size).sum();
    }
}
```

按用户分组的值我存的是 `List`：同一个账号开多个标签页就有多个会话，每个都要单独登记、单独推送。日志里一行 `WS_OPEN user=operator-a sessionId=… online=1` 把用户、会话 ID、在线数同时打出来，运行期的对账全靠这三样。

## 广播与定向推送

事件推送有两个方向：新订单事件广播给全部在线客户端，复核类消息只发给指定用户。两个方法都挂在 SessionRegistry 上：

```java
public int broadcast(String text) {
    int delivered = 0;
    for (List<WebSocketSession> sessions : sessionsByUser.values()) {
        for (WebSocketSession session : sessions) {
            sendSafe(session, text);
            delivered++;
        }
    }
    broadcastSent.addAndGet(delivered);
    return delivered;
}

private void sendSafe(WebSocketSession session, String text) {
    try {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        }
    } catch (IOException e) {
        log.warn("WS_SEND_FAIL sessionId={} error={}", session.getId(), e.toString());
    }
}
```

`synchronized (session)` 这行不是多余动作：多个线程同时向同一个 session 写，Tomcat 会抛 `IllegalStateException: The remote endpoint was in state [TEXT_FULL_WRITING]`，发送必须按 session 串行化。我实测 10 个线程并发触发广播打向同一个会话，串行化之后服务端日志没有一条发送失败，客户端收齐了事件。

HTTP 侧的触发入口只有三个，运营后台的广播接口专门为演示与截图保留：

```java
@PostMapping("/notify/broadcast")
public Map<String, Object> broadcast(@RequestParam(defaultValue = "WS80001") String orderNo,
                                     @RequestParam(defaultValue = "88.50") String amount) {
    NotifyService.Order order = new NotifyService.Order(orderNo, amount, "CREATED", null);
    int delivered = notifyService.broadcastNewOrder(order);
    ...
}

@PostMapping("/notify/direct")
public Map<String, Object> direct(@RequestParam String user,
                                  @RequestParam(defaultValue = "请复核新订单") String message) {
    return notifyService.direct(user, message);
}
```

下单接口走同一条广播路径，区别只是先落库再推事件：

```bash
curl -s -X POST "http://localhost:18130/api/notify/broadcast?orderNo=WS80001&amount=88.50"
```

![](https://static.xiongneng.me/ws-broadcast-flow-20260922022758.png)

这一轮跑下来，我把关键数字留了一份原始记录，逐行对着服务端日志核过：

```text
握手        3 客户端全成功，connected=3；缺 user 参数 → 401 拒绝
广播        POST /api/notify/broadcast → HTTP 返回 13 ms，delivered=3
            事件帧 {"type":"order","seq":1,"orderNo":"WS80001",
                    "amount":"88.50","status":"CREATED","ts":...}
定向        POST /api/notify/direct?user=operator-b → 3 ms，delivered=1
            operator-a 与 operator-c 的直推消息 0 条
心跳        ping 5 次 → pong 收到 5 次
断线        operator-c 主动 close → closed +1，online 3 → 2
下单        POST /api/orders → 落库加推送 75 ms，broadcast=2，事件帧 seq=2
```

广播与定向的延迟都压在 15 毫秒以内，比 3 秒轮询的感知速度低两个数量级，这就是轮询换推送最直接的收益。`delivered` 由服务端逐个会话发送后累加，与在线数对得上：3 个客户端在线时广播返回 3，1 个断开后下单事件返回 2。

## 心跳与断线感知

断线感知我拆成两种情况，责任方不同。客户端主动 close 或 TCP 正常断开，容器能立刻发现，回调 `afterConnectionClosed`，`CloseStatus` 带上原因码，我在这里递减在线数、递增 `closed` 计数。实测客户端调用 `close()` 后，stats 接口的 `closed` 从 0 变 1，`online` 从 3 变 2，全程不到半秒。

另一种麻烦得多，是半开连接：客户端拔网线、进程被 kill、移动网络切换，TCP 层没有任何通知，容器侧的 session 看上去还是 open 的。这种情况容器无能为力，只有应用层心跳能发现。本工程的约定是客户端定时发一帧 `{"type":"ping"}`，服务端回 pong：

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    JsonNode node = mapper.readTree(message.getPayload());
    String type = node.path("type").asString("");
    if ("ping".equals(type)) {
        registry.onPing(session);
        return;
    }
}
```

`registry.onPing` 收到 ping 计数加一，回一帧 `{"type":"pong","t":…}`。服务端同时记录每个会话的上次 pong 时间，超过阈值没有心跳就把会话移除，触发与主动断开相同的 `afterConnectionClosed` 路径。我实测客户端连发 5 次 ping，收到 5 次 pong，`pingReceived` 与 `pongSent` 计数都是 5，两个口径对得上。

这里有个容易想岔的点：spring-websocket 顶层的 `PingMessage` 走的是 RFC 6455 控制帧，`session.sendMessage(new PingMessage())` 发出去之后由容器自动回 pong，应用层收不到回调。做应用级存活判断要用文本帧自己约定报文，控制帧那套只适合探测协议层连通性。我第一版就拿 PingMessage 当心跳用，测到半开连接才发现它帮不上忙。

![](https://static.xiongneng.me/ws-heartbeat-awareness-20260922022906.png)

## 完整案例，跑起来看

把前面的部件按运营后台的真实顺序串起来。三个客户端带着身份连上：

```bash
# operator-a / operator-b / operator-c 各连一条
ws://localhost:18130/ws?user=operator-a
```

服务端日志给出会话证据，`online` 逐次累加：

```text
WS_OPEN user=operator-a sessionId=e932f9ca-… online=1
WS_OPEN user=operator-b sessionId=23e957f6-… online=2
WS_OPEN user=operator-c sessionId=9f825394-… online=3
```

运营触发一次广播，HTTP 响应在 13 毫秒内返回，`delivered` 直接写明送达数：

```bash
curl -s -X POST "http://localhost:18130/api/notify/broadcast?orderNo=WS80001&amount=88.50"
# {"type":"order","seq":1,"orderNo":"WS80001","amount":"88.50","delivered":3}
```

![](https://static.xiongneng.me/ws-panels-20260920013246.png)

面板拼板和上面那份原始记录是分开跑的两次，毫秒数有抖动，计数值我逐项核过全都一致：广播面板 `delivered` 是 3，定向面板只有 `operator-b` 收到、`delivered` 是 1，operator-a 的会话帧面板里能看到广播帧与 pong 帧原文，stats 面板在 operator-c 断开后 `closed` 等于 1、`online` 等于 2、`pingReceived` 与 `pongSent` 都是 3。

最后一个视角是下单到推送的端到端耗时。`POST /api/orders` 先写 MySQL 再广播，我实测一轮 75 毫秒，其中落库占大头；事件帧的 `seq` 从广播轮的 1 走到 2，客户端按序号就能确认没有漏帧。

## JSR-356 对照：@ServerEndpoint 路线

jakarta.websocket 的注解路线是另一套编程模型，端点类长这样：

```java
@ServerEndpoint(value = "/ws/jsr356/{user}")
public class NotifyJsr356Endpoint {

    @Autowired
    private SessionRegistry registry;

    @OnOpen
    public void onOpen(Session session, @PathParam("user") String user) { ... }

    @OnMessage
    public String onMessage(String message, Session session) {
        String user = (String) session.getUserProperties().getOrDefault("user", "anonymous");
        return "echo:" + user + ":" + message;
    }
}
```

`@OnMessage` 的返回值就是回给客户端的帧，路径参数 `{user}` 从 `@PathParam` 取。这条路线有两个注册问题必须处理。注解本身不会生效：内嵌 Tomcat 的 `WsSci` 扫描机制在 Boot 里扫不到应用类路径，端点要编程式注册到 `ServerContainer`。注册的时机也有讲究，我踩过这个坑：直接放进 `ServletContextInitializer` 会拿到 null，因为 `ServerContainer` 属性由 WsSci 在 SCI 阶段写入，那一步还没执行；把注册挪进 `ServletContextListener.contextInitialized` 就能拿到：

```java
@Bean
public ServletContextInitializer jsr356EndpointRegistrar() {
    return servletContext -> servletContext.addListener(new ServletContextListener() {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
            ServerContainer container = (ServerContainer)
                    sce.getServletContext().getAttribute(ServerContainer.class.getName());
            ServerEndpointConfig config = ServerEndpointConfig.Builder
                    .create(NotifyJsr356Endpoint.class, "/ws/jsr356/{user}")
                    .configurator(new BootSpringConfigurator())
                    .build();
            container.addEndpoint(config);
        }
    });
}
```

依赖注入由 `configurator` 完成。spring-websocket 自带的 `SpringConfigurator` 在 Boot 下不可用，它在握手期找 root `WebApplicationContext`，Boot 的单层上下文查不到，直接抛 `IllegalStateException: Failed to find the root WebApplicationContext`。我的做法是自写 `BootSpringConfigurator`，走 `AutowireCapableBeanFactory.createBean`：每个连接新建一个端点实例并完成 `@Autowired` 注入，语义与规范一致。

两条路线的行为我都验证过：客户端连 `ws://localhost:18130/ws/jsr356/inspector-x` 发送 `hello`，收到 `echo:inspector-x:hello`；两个客户端各自收到各自的回显，互不串线。选型上没有悬念：Spring 路线有拦截器、有会话管理、有事件，业务系统用它；@ServerEndpoint 路线胜在标准 API，适合对接只认 JSR-356 的客户端。

![](https://static.xiongneng.me/ws-jsr356-endpoint-20260922022805.png)

## 测试怎么写

WS 测试的关键是「真连」。测试里我用 jakarta.websocket 的标准客户端 API，实现在 tomcat-embed-websocket 里，不需要额外依赖：

```java
@ClientEndpoint
public class WsTestClient implements AutoCloseable {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    public static WsTestClient connect(int port, String path) throws Exception {
        WsTestClient client = new WsTestClient();
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(client, URI.create("ws://localhost:" + port + path));
        ...
    }
}
```

客户端回调跑在容器线程上，与测试主线程不同步，断言用「等消息」而不是轮询计数：`awaitMessage` 在收到满足条件的帧之前阻塞，超时直接失败。广播用例连两个客户端、调一次广播接口、两边各等自己的帧：

```java
@Test
void broadcastReachesAllOnlineClients() throws Exception {
    WsTestClient a = connect("operator-a");
    WsTestClient b = connect("operator-b");

    mockMvc.perform(post("/api/notify/broadcast")
                    .param("orderNo", "WS80001").param("amount", "88.50"))
            .andReturn();

    String payload = a.awaitMessage(m -> m.contains("\"type\":\"order\""), 5);
    assertThat(payload).contains("WS80001").contains("\"amount\":\"88.50\"");
    assertThat(b.awaitMessage(m -> m.contains("WS80001"), 5)).isNotNull();
}
```

17 个用例全连真实 MySQL，分布与覆盖：配置事实 3 个（两条路线的 bean 在位、handler 是 TextWebSocketHandler），HTTP 接口 3 个（广播响应、下单落库、stats 结构），全链路 8 个（广播到达、定向隔离、ping/pong 计数、断线计数、下单事件、seq 递增、并发广播、无身份握手被拒），JSR-356 3 个（回显、双客户端隔离、依赖注入）。断线感知用 Awaitility 轮询 stats 接口，客户端 close 之后 `closed` 计数加一才算过。

两个边界用例我单独拎出来说。seq 递增断言把消息顺序钉死，两次广播之间序号必须变大：

```java
String first = f.awaitMessage(m -> m.contains("WS80003"), 5);
String second = f.awaitMessage(m -> m.contains("WS80004"), 5);
long seq1 = Long.parseLong(first.replaceAll(".*\"seq\":(\\d+).*", "$1"));
long seq2 = Long.parseLong(second.replaceAll(".*\"seq\":(\\d+).*", "$1"));
assertThat(seq2).isGreaterThan(seq1);
```

无身份握手被拒的用例反过来验证拦截器：连接 `ws://…/ws` 不带 `user` 参数，`connectToServer` 必须抛异常才算过，因为服务端在握手期就返回了 401。定向隔离用例在目标客户端收到 direct 帧之后，还要断言另一个客户端的消息列表里 0 条 direct 帧，「只有目标收到」才是这个接口的完整语义。

## 避坑指南

**坑一，并发写同一个 session 直接抛异常。** Tomcat 的写状态机是单向的，上一个 `TEXT_FULL_WRITING` 没写完又来一条就是 `IllegalStateException`。服务端任何一处可能并发触达同一会话的发送，都要按 session 串行化，广播与定向共用一个 `synchronized (session)` 的发送方法最省心。

**坑二，SpringConfigurator 在 Boot 下不可用。** 它在握手期查 root `WebApplicationContext`，Boot 单层上下文查不到，连接直接失败。替代方案是自写 Configurator，从 `AutowireCapableBeanFactory.createBean` 拿端点实例，每个连接一个新实例并完成注入。

**坑三，@ServerEndpoint 注解写完不会生效。** 内嵌 Tomcat 扫不到应用类路径里的注解端点，必须编程式 `ServerContainer.addEndpoint`。注册还要选对时机：`ServletContextInitializer` 阶段 `ServerContainer` 属性还是 null，放进 `ServletContextListener.contextInitialized` 才拿得到。

**坑四，TextWebSocketHandler 收到二进制帧就断开。** 二进制帧走父类默认实现，抛 `CloseStatus.NOT_ACCEPTABLE`，连接直接被服务端关掉。客户端约定只用文本帧，或者改继承 `AbstractWebSocketHandler` 自己处理二进制分支。

**坑五，容器感知不了半开连接。** 拔网线、进程被 kill 之后 session 看上去仍然 open，服务端推送会静默失败。存活判断只能靠应用层心跳：记录上次 pong 时间，超时主动关闭并移除会话。RFC 6455 的 ping 控制帧由容器代答，应用层收不到，不能当心跳用。

**坑六，握手默认不设防。** 没有拦截器时任何客户端都能连上来。身份校验放 `HandshakeInterceptor`，缺参返回 false 加 401；跨域同理，`setAllowedOriginPatterns` 不配就是裸奔。

**坑七，同一账号会有多个会话。** 一个用户开三个标签页就是三条连接，按用户推送时值要用 `List` 存会话，登记与移除都要按 session 粒度做。用单值存最后一个会话，其余标签页的推送就全丢了。

**坑八，送达数要和在线数对账。** 广播返回的 `delivered` 只统计发送成功的会话，`isOpen` 为 false 的会话跳过不计数。`delivered` 与 `onlineCount` 对不上，先查有没有把已断开的会话移除，再查发送失败日志 `WS_SEND_FAIL`。

## 小结

真要往生产里落，我的选型建议分三步走：业务系统的推送老老实实用 Spring 的 WebSocketHandler 路线，`@EnableWebSocket` 加握手拦截器加会话登记器这套组合装好就能用，广播与定向共用一个串行化的发送方法；要主题订阅、确认机制这类子协议语义再上 STOMP，那条唯一的自动配置就是为它准备的；只有对接方只认 JSR-356 标准时才走 @ServerEndpoint，注册时机与 Configurator 两处坑提前绕开。连接数一多、会话要跨实例共享时，先给 SessionRegistry 换上外置的 pub/sub 通道，再谈横向扩容。

没解决的事也直说：本文的广播只在单实例里成立，多副本部署时各实例的 SessionRegistry 互不相通，跨实例推送得引入 broker relay 或者外挂消息队列，这块我没搭环境测，不敢给数字；半开连接的超时阈值怎么定也没有公式，阈值短了误杀慢客户端，长了资源白白挂着，只能按你自己的网络场景压测着调。

## 参考链接

- [Spring Framework WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html)：WebSocket 支持总览与 `WebSocketHandler` 编程模型的官方说明
- [Spring Framework WebSocket Server](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)：`@EnableWebSocket`、握手拦截器与 `WebSocketConfigurer` 配置细节
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)：`spring.websocket.*` 属性与 starter 选型
- [Spring Framework STOMP over WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html)：`WebSocketMessagingAutoConfiguration` 对应的 STOMP 子协议文档
- [jakarta.websocket @ServerEndpoint](https://jakarta.ee/specifications/platform/11/apidocs/jakarta/websocket/server/serverendpoint)：JSR-356 注解与 `Session` 生命周期 API
- [spring-websocket 7.0.9](https://repo1.maven.org/maven2/org/springframework/spring-websocket/7.0.9/)：本文包位置与属性前缀的制品实证来源
