package com.xncoding.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSR-356 对照路线：@ServerEndpoint 注册到内嵌 Tomcat 的 ServerContainer，
 * @OnMessage 的返回值就是回给客户端的帧。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Jsr356EndpointTest {

    @LocalServerPort
    private int port;

    private final CopyOnWriteArrayList<JsrClient> opened = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (JsrClient c : opened) {
            c.close();
        }
    }

    /** 最小客户端：@ClientEndpoint 注解风格，收到的帧记进列表。 */
    @jakarta.websocket.ClientEndpoint
    public static class JsrClient implements AutoCloseable {
        private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        private jakarta.websocket.Session session;

        @jakarta.websocket.OnMessage
        public void onMessage(String msg) {
            received.add(msg);
        }

        String awaitReply(long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                if (!received.isEmpty()) {
                    return received.get(0);
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no reply; got: " + received);
        }

        void send(String text) throws Exception {
            session.getBasicRemote().sendText(text);
        }

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }

    private JsrClient connect(String user) throws Exception {
        JsrClient client = new JsrClient();
        jakarta.websocket.WebSocketContainer container = jakarta.websocket.ContainerProvider.getWebSocketContainer();
        jakarta.websocket.Session session = container.connectToServer(client,
                java.net.URI.create("ws://localhost:" + port + "/ws/jsr356/" + user));
        client.session = session;
        opened.add(client);
        return client;
    }

    @Test
    void jsr356EchoIncludesPathParameterUser() throws Exception {
        JsrClient client = connect("inspector-x");
        client.send("hello");
        String reply = client.awaitReply(5);
        assertThat(reply).isEqualTo("echo:inspector-x:hello");
    }

    @Test
    void jsr356TwoClientsAreIsolated() throws Exception {
        JsrClient a = connect("inspector-a");
        JsrClient b = connect("inspector-b");
        a.send("msg-a");
        b.send("msg-b");
        assertThat(a.awaitReply(5)).isEqualTo("echo:inspector-a:msg-a");
        assertThat(b.awaitReply(5)).isEqualTo("echo:inspector-b:msg-b");
    }

    @Test
    void jsr356HandshakeProvesSpringConfiguratorInjection() throws Exception {
        // SpringConfigurator 在握手期创建端点实例并注入 @Autowired 字段，
        // 注入失败会直接抛异常导致握手失败——连接成功本身就证明 DI 生效
        JsrClient client = connect("inspector-c");
        assertThat(client.session.isOpen()).isTrue();
    }
}
