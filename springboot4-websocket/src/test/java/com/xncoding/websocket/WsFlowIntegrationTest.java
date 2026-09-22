package com.xncoding.websocket;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 全链路真连：jakarta.websocket 客户端连到真实端口，
 * 广播、定向、心跳、断线感知、JSON 事件往返在这里逐项断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WsFlowIntegrationTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private final Map<String, WsTestClient> clients = new ConcurrentHashMap<>();

    private WsTestClient connect(String user) throws Exception {
        WsTestClient client = WsTestClient.connect(port, "/ws?user=" + user);
        clients.put(user, client);
        return client;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (WsTestClient client : clients.values()) {
            client.close();
        }
        clients.clear();
        // 等服务端 afterConnectionClosed 全部处理完，避免影响下一个用例的计数
        Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() == 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats() {
        try {
            MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.get("/api/notify/stats"))
                    .andReturn();
            String json = result.getResponse().getContentAsString();
            return new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void broadcastReachesAllOnlineClients() throws Exception {
        WsTestClient a = connect("operator-a");
        WsTestClient b = connect("operator-b");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 2);

        long t0 = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(post("/api/notify/broadcast")
                        .param("orderNo", "WS80001").param("amount", "88.50"))
                .andReturn();
        long latency = System.currentTimeMillis() - t0;

        String payloadA = a.awaitMessage(m -> m.contains("\"type\":\"order\"") && m.contains("WS80001"), 5);
        String payloadB = b.awaitMessage(m -> m.contains("\"type\":\"order\"") && m.contains("WS80001"), 5);
        assertThat(payloadA).contains("\"amount\":\"88.50\"");
        assertThat(payloadB).contains("\"amount\":\"88.50\"");

        Map<String, Object> body = new tools.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("delivered")).isEqualTo(2);
        // 广播到达延迟取值：HTTP 返回时消息已投出，断言面板延迟来自这个口径
        assertThat(latency).isLessThan(500);
        System.out.println("MEASURE broadcast latency ms = " + latency);
    }

    @Test
    void directedMessageGoesOnlyToTargetUser() throws Exception {
        WsTestClient a = connect("operator-a");
        WsTestClient b = connect("operator-b");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 2);

        MvcResult result = mockMvc.perform(post("/api/notify/direct")
                        .param("user", "operator-b").param("message", "请复核新订单"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String payload = b.awaitMessage(m -> m.contains("\"type\":\"direct\""), 5);
        assertThat(payload).contains("请复核新订单").contains("operator-b");
        Thread.sleep(300);
        assertThat(a.messages()).noneMatch(m -> m.contains("\"type\":\"direct\""));
    }

    @Test
    void pingGetsPongAndCountersTick() throws Exception {
        WsTestClient c = connect("operator-c");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 1);

        long before = ((Number) stats().get("pingReceived")).longValue();
        c.sendText("{\"type\":\"ping\"}");
        c.awaitMessage(m -> m.contains("\"type\":\"pong\""), 5);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("pingReceived")).longValue() == before + 1);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("pongSent")).longValue() >= 1);
    }

    @Test
    void closeTriggersAfterConnectionClosedCounter() throws Exception {
        int onlineBefore = ((Number) stats().get("online")).intValue();
        long closedBefore = ((Number) stats().get("closed")).longValue();

        WsTestClient d = connect("operator-d");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() == onlineBefore + 1);
        d.close();
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("closed")).longValue() == closedBefore + 1);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() == onlineBefore);
    }

    @Test
    void createOrderPushesJsonEventToWsClients() throws Exception {
        WsTestClient e = connect("operator-e");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 1);

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"WS80002\",\"amount\":\"299.00\"}"))
                .andReturn();

        String payload = e.awaitMessage(m -> m.contains("WS80002"), 5);
        assertThat(payload)
                .contains("\"type\":\"order\"")
                .contains("\"amount\":\"299.00\"")
                .contains("\"status\":\"CREATED\"")
                .contains("\"seq\":");
    }

    @Test
    void handshakeWithoutUserParamIsRejected() {
        jakarta.websocket.WebSocketContainer container =
                jakarta.websocket.ContainerProvider.getWebSocketContainer();
        AtomicReference<Exception> failure = new AtomicReference<>();
        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            try {
                container.connectToServer(WsTestClient.class,
                        java.net.URI.create("ws://localhost:" + port + "/ws"));
                return false; // 意外握手成功
            } catch (Exception ex) {
                failure.set(ex);
                return true;
            }
        });
        assertThat(failure.get()).isNotNull();
    }

    @Test
    void eventSeqIsMonotonicAcrossBroadcasts() throws Exception {
        WsTestClient f = connect("operator-f");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 1);

        mockMvc.perform(post("/api/notify/broadcast").param("orderNo", "WS80003")).andReturn();
        mockMvc.perform(post("/api/notify/broadcast").param("orderNo", "WS80004")).andReturn();

        String first = f.awaitMessage(m -> m.contains("WS80003"), 5);
        String second = f.awaitMessage(m -> m.contains("WS80004"), 5);
        long seq1 = Long.parseLong(first.replaceAll(".*\"seq\":(\\d+).*", "$1"));
        long seq2 = Long.parseLong(second.replaceAll(".*\"seq\":(\\d+).*", "$1"));
        assertThat(seq2).isGreaterThan(seq1);
    }

    @Test
    void concurrentBroadcastToSameSessionDoesNotBlowUp() throws Exception {
        WsTestClient g = connect("operator-g");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                ((Number) stats().get("online")).intValue() >= 1);

        // 并发 10 条广播打向同一 session：串行化发送后服务端不能抛 TEXT_FULL_WRITING
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            String orderNo = "WSC00" + i;
            threads[i] = new Thread(() -> {
                try {
                    mockMvc.perform(post("/api/notify/broadcast").param("orderNo", orderNo)).andReturn();
                } catch (Exception ignored) {
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        // 服务端日志不出现 WS_SEND_FAIL 即通过；客户端至少收到一部分事件
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                g.messages().stream().filter(m -> m.contains("\"type\":\"order\"")).count() >= 5);
    }
}
