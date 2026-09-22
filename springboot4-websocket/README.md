# springboot4-websocket：使用 WebSocket 实时通信

Spring Boot 4.1.1 + Framework 7.0.9 的 WebSocket 教程工程：运营后台实时收到新订单事件，
客户端断线要能感知。配套文章见 `../articles/SpringBoot4系列13 - 使用WebSocket实时通信.md`。

## 运行

前置：JDK 21、MySQL（默认连 192.168.1.97:3306，可用 `DB_HOST` 等环境变量覆盖，
库名 `springboot4_websocket`，表结构启动时自动建）。

```bash
mvn spring-boot:run
# 应用监听 http://localhost:18130
```

WS 端点两个：

- `ws://localhost:18130/ws?user=operator-a` —— Spring 路线（WebSocketConfigurer + TextWebSocketHandler），`user` 是必填身份参数，缺了握手直接 401；
- `ws://localhost:18130/ws/jsr356/{user}` —— JSR-356 路线（@ServerEndpoint），发什么回什么（echo）。

## 测试

```bash
mvn test
```

17 个用例全连真实 MySQL：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| WsConfigTest | 3 | 配置事实：两条路线的 bean 在位、handler 是 TextWebSocketHandler |
| NotifyApiTest | 3 | HTTP 接口：广播响应、下单落库、stats 结构 |
| WsFlowIntegrationTest | 8 | jakarta.websocket 客户端真连：广播、定向、心跳、断线计数、下单事件、seq 递增、并发广播、无身份握手被拒 |
| Jsr356EndpointTest | 3 | JSR-356 回显、双客户端隔离、SpringConfigurator 注入 |

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/orders | 下单：落库后向全部在线客户端广播新订单事件 |
| POST | /api/notify/broadcast?orderNo=&amount= | 运营触发广播（不落库），返回送达数 |
| POST | /api/notify/direct?user=&message= | 定向推送，只有目标用户的会话收到 |
| GET | /api/notify/stats | 运行计数：online / connected / closed / pingReceived / pongSent / broadcastSent / directedSent |

## 实测结论（2026-09-20，S4 验证脚本同源）

- 3 客户端握手后广播：HTTP 返回 13ms，delivered=3，事件帧
  `{"type":"order","seq":1,"orderNo":"WS80001","amount":"88.50","status":"CREATED",...}`；
- 定向推送 3ms，只有目标用户收到（另一用户 0 条）；
- 心跳 ping 5 次 → pong 5 次（pingReceived / pongSent 计数一致）；
- 客户端主动 close 后 `afterConnectionClosed` 触发，closed 计数 +1，online 3 → 2；
- 下单到推送 75ms（含 MySQL 落库）；
- 应用日志 ERROR 0 行。

## 关键实现点

- 握手拦截器 `HandshakeAuthInterceptor`：query 参数 `user` 是身份，缺失返回 401；
- 会话登记 `SessionRegistry`：`ConcurrentHashMap<String, List<WebSocketSession>>` 按用户分组，
  发送按 session 串行化（并发写会撞 Tomcat 的 TEXT_FULL_WRITING）；
- 心跳：客户端发 `{"type":"ping"}`，服务端回 pong 文本并计数；容器对半开连接无能为力，
  断线感知要靠应用层心跳超时 + `afterConnectionClosed`；
- JSR-356 路线：注解不会自动生效，要编程式 `ServerContainer.addEndpoint`，
  且注册必须放在 `ServletContextListener.contextInitialized`（SCI 阶段 ServerContainer 属性
  还不存在）；`SpringConfigurator` 在 Boot 下不可用，本工程自写 `BootSpringConfigurator`
  走 `AutowireCapableBeanFactory.createBean` 完成依赖注入。
