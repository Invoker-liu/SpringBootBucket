package com.xncoding.websocket.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话登记与发送中枢：按 user 分组登记会话，广播与定向发送都走这里。
 * 计数器是取值单的直接来源（连接数、ping/pong、断线事件、送达数）。
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, List<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final AtomicLong connected = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong pingReceived = new AtomicLong();
    private final AtomicLong pongSent = new AtomicLong();
    private final AtomicLong broadcastSent = new AtomicLong();
    private final AtomicLong directedSent = new AtomicLong();

    public void register(String user, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(user, k -> new CopyOnWriteArrayList<>()).add(session);
        connected.incrementAndGet();
        log.info("WS_OPEN user={} sessionId={} online={}", user, session.getId(), onlineCount());
    }

    public void unregister(String user, WebSocketSession session) {
        List<WebSocketSession> sessions = sessionsByUser.get(user);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(user, sessions);
            }
        }
        closed.incrementAndGet();
        log.info("WS_CLOSE user={} sessionId={} online={}",
                user, session.getId(), onlineCount());
    }

    public int onlineCount() {
        return sessionsByUser.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 同一 session 的并发写要串行化：Tomcat 在 TEXT_FULL_WRITING 状态再写直接抛异常。
     */
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

    /** 定向发送：只发给指定 user 的全部会话，返回送达数。 */
    public int sendToUser(String user, String text) {
        List<WebSocketSession> sessions = sessionsByUser.getOrDefault(user, List.of());
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            sendSafe(session, text);
            delivered++;
        }
        directedSent.addAndGet(delivered);
        return delivered;
    }

    /** 广播：发给全部在线会话，返回送达数。 */
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

    void onPing(WebSocketSession session) throws IOException {
        pingReceived.incrementAndGet();
        synchronized (session) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\",\"t\":" + System.currentTimeMillis() + "}"));
        }
        pongSent.incrementAndGet();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "online", onlineCount(),
                "connected", connected.get(),
                "closed", closed.get(),
                "pingReceived", pingReceived.get(),
                "pongSent", pongSent.get(),
                "broadcastSent", broadcastSent.get(),
                "directedSent", directedSent.get(),
                "users", sessionsByUser.keySet()
        );
    }

    long connectedCount() {
        return connected.get();
    }

    long closedCount() {
        return closed.get();
    }

    long pingCount() {
        return pingReceived.get();
    }

    long pongCount() {
        return pongSent.get();
    }
}
