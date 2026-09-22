package com.xncoding.websocket;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * jakarta.websocket 标准客户端（实现来自 tomcat-embed-websocket）：
 * 真连 ws://localhost:{port}/ws 做收发断言，回调线程与应用线程不同，
 * 等待一律用 latch，不轮询。
 */
@ClientEndpoint
public class WsTestClient implements AutoCloseable {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile Session session;

    public static WsTestClient connect(int port, String path) throws Exception {
        WsTestClient client = new WsTestClient();
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        Session session = container.connectToServer(client,
                URI.create("ws://localhost:" + port + path));
        client.session = session;
        if (!client.openLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("handshake timeout: " + path);
        }
        return client;
    }

    @OnOpen
    public void onOpen(Session session) {
        openLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message) {
        messages.add(message);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        closeLatch.countDown();
    }

    public List<String> messages() {
        return messages;
    }

    /** 等到一条满足条件的消息，超时抛断言错误。 */
    public String awaitMessage(Predicate<String> predicate, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            for (String m : messages) {
                if (predicate.test(m)) {
                    return m;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no matching message in " + timeoutSeconds + "s; got: " + messages);
    }

    public boolean awaitClose(long timeoutSeconds) throws InterruptedException {
        return closeLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    public void sendText(String text) throws Exception {
        session.getBasicRemote().sendText(text);
    }

    @Override
    public void close() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
